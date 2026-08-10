package com.canglan.world.buff;

import java.util.stream.Collectors;

import java.util.ArrayList;
import java.util.List;

import com.canglan.core.effect.DamageMod;
import com.canglan.core.effect.EffectDef;
import com.canglan.core.effect.Operator;
import com.canglan.core.effect.StatMod;
import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.data.buff.Buff;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.unit.Unit;

/**
 * BuffManager — Unit 的 Buff 管理器。对应 C# BuffManager。
 * Buff 不进 TagSet（不污染进化条件），只影响数值叠加层。
 * STAT_MOD 效果通过全量重建 Unit 的 Buff 属性快照来应用/移除（与标签层同样的无状态哲学）。
 * 订阅属主 = 本实例，不会被 recalculateTags 的 unsubscribeAll(unit) 清理。
 */
public final class BuffManager {

    private final Unit owner;
    private final EventBus eventBus;
    private final EffectEngine effectEngine;
    private final List<Buff> activeBuffs = new ArrayList<>();

    public BuffManager(Unit owner, EventBus bus, EffectEngine engine) {
        this.owner = owner;
        this.eventBus = bus;
        this.effectEngine = engine;
        bus.subscribeWithOwner(EventTypes.TURN_END, e -> onTurnEnd(), this);
    }

    public List<Buff> getActiveBuffs() { return new ArrayList<>(activeBuffs); }

    public boolean hasBuff(String buffId) {
        return activeBuffs.stream().anyMatch(b -> b.id().equals(buffId));
    }

    public void addBuff(Buff buff) {
        if (buff == null) return;
        Buff existing = activeBuffs.stream()
                .filter(b -> b.id().equals(buff.id()))
                .findFirst().orElse(null);
        if (existing != null) {
            if (existing.canStack()) {
                existing.incrementStacks();
                existing.refresh();
            } else {
                existing.refresh();   // 不可叠加 → 刷新持续时间
            }
        } else {
            activeBuffs.add(buff);
            // TRIGGER 效果：以 buff 为属主注册监听，移除时统一清理
            effectEngine.registerEffectTriggers(owner, buff.effects(), eventBus, buff);
        }
        rebuildStatMods();
        eventBus.emit(EventTypes.BUFF_APPLIED, owner, buff);
    }

    public void removeBuff(String buffId) {
        Buff buff = activeBuffs.stream()
                .filter(b -> b.id().equals(buffId))
                .findFirst().orElse(null);
        if (buff == null) return;
        activeBuffs.remove(buff);
        eventBus.unsubscribeAll(buff);   // 清理 TRIGGER 监听
        rebuildStatMods();
        eventBus.emit(EventTypes.BUFF_REMOVED, owner, buff);
    }

    /** 回合结束：倒计时 → 清理过期Buff（不触发 recalculateTags）。 */
    public void onTurnEnd() {
        for (Buff buff : activeBuffs) buff.tickDown();
        List<Buff> expired = activeBuffs.stream().filter(Buff::isExpired).collect(Collectors.toList());
        if (expired.isEmpty()) return;
        for (Buff buff : expired) {
            activeBuffs.remove(buff);
            eventBus.unsubscribeAll(buff);
            eventBus.emit(EventTypes.BUFF_EXPIRED, owner, buff);
        }
        rebuildStatMods();
    }

    /** 无状态重建：清空快照后重放所有活跃Buff的 STAT_MOD（含层数倍乘）。 */
    private void rebuildStatMods() {
        owner.resetBuffStats();
        for (Buff buff : activeBuffs) {
            for (EffectDef effect : buff.effects()) {
                if (effect instanceof StatMod s) {
                    float value = s.operator() == Operator.ADD ? s.value() * buff.currentStacks() : s.value();
                    owner.applyBuffStat(s.target(), s.operator(), value);
                }
            }
        }
    }

    /** 伤害加成查询（ATK ADD 效果之和）。 */
    public float getDamageBonus() { return sumStatMods("ATK"); }

    /** 防御加成查询（DEF ADD 效果之和）。 */
    public float getDefenseBonus() { return sumStatMods("DEF"); }

    /** 针对目标的条件增伤倍率（DAMAGE_MOD 效果，伤害计算时查询）。 */
    public float getDamageModMultiplier(Unit target) {
        float multiplier = 1f;
        for (Buff buff : activeBuffs) {
            for (EffectDef effect : buff.effects()) {
                if (effect instanceof DamageMod dm && dm.matches(target)) {
                    multiplier = switch (dm.operator()) {
                        case ADD -> multiplier + dm.value();
                        case MULTIPLY -> multiplier * dm.value();
                        case SET -> dm.value();
                    };
                }
            }
        }
        return multiplier;
    }

    private float sumStatMods(String target) {
        float sum = 0f;
        for (Buff buff : activeBuffs) {
            for (EffectDef effect : buff.effects()) {
                if (effect instanceof StatMod s && s.target().equals(target) && s.operator() == Operator.ADD) {
                    sum += s.value();
                }
            }
        }
        return sum;
    }
}

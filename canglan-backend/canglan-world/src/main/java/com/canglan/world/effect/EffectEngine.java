package com.canglan.world.effect;

import java.util.List;
import java.util.Random;

import com.canglan.core.effect.DamageMod;
import com.canglan.core.effect.EffectDef;
import com.canglan.core.effect.StatMod;
import com.canglan.core.effect.TriggerDef;
import com.canglan.core.eventbus.Event;
import com.canglan.core.eventbus.EventBus;
import com.canglan.core.tag.Tag;
import com.canglan.data.buff.BuffFactory;
import com.canglan.world.unit.Unit;

/**
 * EffectEngine — 效果应用到实例。对应 C# EffectEngine。
 * STAT_MOD → 属性快照；DAMAGE_MOD → 条件增伤（伤害计算时查询）；
 * TRIGGER → EventBus 注册监听；FLAG → 纯标记。
 * GAIN_BUFF 依赖显式注入的 BuffFactory（替代 C# 静态 BuffFactory.Instance）。
 */
public final class EffectEngine {

    private final Random rng = new Random();
    private final BuffFactory buffFactory;

    public EffectEngine(BuffFactory buffFactory) {
        this.buffFactory = buffFactory;
    }

    /** 属性效果：逐个叠加到 Unit 的标签属性快照。 */
    public void applyStatMods(Unit unit, List<Tag> tags) {
        unit.resetTagStats();
        for (Tag tag : tags) {
            for (EffectDef effect : tag.effects()) {
                if (effect instanceof StatMod s) {
                    unit.applyTagStat(s.target(), s.operator(), s.value());
                }
            }
        }
    }

    /** 触发效果：在 EventBus 注册监听（订阅属主 = unit，recalculateTags 时统一清理）。 */
    public void registerTriggers(Unit unit, List<Tag> tags, EventBus bus) {
        for (Tag tag : tags) {
            for (EffectDef effect : tag.effects()) {
                if (effect instanceof TriggerDef t) {
                    bus.subscribeWithOwner(t.on(), evt -> {
                        if (!t.shouldFire(evt, unit, rng)) return;
                        executeTriggerAction(unit, t, evt, bus);
                    }, unit);
                }
            }
        }
    }

    /** 为任意效果列表（Buff/套装）注册 TRIGGER 监听，属主 = owner（移除时 unsubscribeAll(owner)）。 */
    public void registerEffectTriggers(Unit unit, List<EffectDef> effects, EventBus bus, Object owner) {
        for (EffectDef effect : effects) {
            if (effect instanceof TriggerDef t) {
                bus.subscribeWithOwner(t.on(), evt -> {
                    if (!t.shouldFire(evt, unit, rng)) return;
                    executeTriggerAction(unit, t, evt, bus);
                }, owner);
            }
        }
    }

    /** 技能/Buff 场景下的通用效果应用：作用在 actor 对 target 上。 */
    public void applyEffect(Unit actor, Unit target, EffectDef effect, EventBus bus) {
        if (effect instanceof StatMod s) {
            // 技能伤害型 STAT_MOD(对目标)：视为属性修正直接作用于目标标签快照
            target.applyTagStat(s.target(), s.operator(), s.value());
        } else if (effect instanceof DamageMod dm) {
            float dmg = Math.max(1f, dm.value() * 10f); // 基础伤害由调用方主导，这里仅做增伤传递
            if (dm.matches(target)) target.takeDamage(dmg, actor, bus);
        }
        // TRIGGER 只在注册路径生效；FLAG 为纯标记
    }

    /** 执行 TRIGGER 动作：GAIN_BUFF / HEAL / GAIN_TAG / EMIT_EVENT。 */
    public void executeTriggerAction(Unit owner, TriggerDef trigger, Event evt, EventBus bus) {
        switch (trigger.action()) {
            case "GAIN_BUFF", "APPLY_BUFF" -> {
                Object b = trigger.params().get("buffId");
                Object d = trigger.params().get("duration");
                String buffId = b != null ? b.toString() : null;
                int duration = d instanceof Number n ? n.intValue() : 3;
                if (buffId != null) owner.buffManager().addBuff(buffFactory.createFromRegistry(buffId, duration));
            }
            case "HEAL" -> {
                Object h = trigger.params().get("amount");
                int amount = h instanceof Number n ? n.intValue() : 10;
                owner.heal(amount);
            }
            case "GAIN_TAG" -> {
                Object g = trigger.params().get("tagId");
                String tagId = g != null ? g.toString() : null;
                if (tagId != null) {
                    owner.questTagIds().add(tagId);
                    owner.recalculateTags();
                }
            }
            case "EMIT_EVENT" -> {
                Object e = trigger.params().get("eventType");
                String eventType = e != null ? e.toString() : null;
                if (eventType != null && bus != null) bus.emit(eventType, owner);
            }
            default -> { }
        }
    }
}

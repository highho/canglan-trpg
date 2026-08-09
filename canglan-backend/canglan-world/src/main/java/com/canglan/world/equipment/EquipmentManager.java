package com.canglan.world.equipment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.canglan.core.effect.EffectDef;
import com.canglan.core.effect.Operator;
import com.canglan.core.effect.StatMod;
import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.data.buff.BuffFactory;
import com.canglan.data.equipment.EquipDef;
import com.canglan.data.equipment.EquipRegistry;
import com.canglan.data.equipment.EquipSlot;
import com.canglan.data.equipment.SetBonusDef;
import com.canglan.data.equipment.SetBonusRegistry;
import com.canglan.world.unit.Unit;

/**
 * EquipmentManager — 装备管理。对应 C# EquipmentManager。
 * 铁律：装备永远不进 TagSet。装备效果 = 永久Buff（含 baseStats 转 StatMod），
 * 套装用 N件计数 → 追加套装Buff；穿脱不触发 recalculateTags（标签注入除外）。
 * P6 简化：词缀 CARRY 负重加成不迁移（无词缀系统）。
 */
public final class EquipmentManager {

    /** 修理结果。对应 C# RepairResult。 */
    public record RepairResult(boolean success, String error) {
        public static RepairResult ok() { return new RepairResult(true, null); }
        public static RepairResult fail(String err) { return new RepairResult(false, err); }
    }

    private final Unit owner;
    private final EventBus eventBus;
    private final SetBonusRegistry setRegistry;
    private final EquipRegistry equipRegistry;
    private final Map<EquipSlot, Equip> equipped = new EnumMap<>(EquipSlot.class);
    private final Map<String, Integer> activeSetBonuses = new HashMap<>();   // setId → 件数

    public EquipmentManager(Unit owner, EventBus bus, SetBonusRegistry setRegistry,
                            EquipRegistry equipRegistry) {
        this.owner = owner;
        this.eventBus = bus;
        this.setRegistry = setRegistry;
        this.equipRegistry = equipRegistry;
        bus.subscribeWithOwner(EventTypes.BATTLE_END, e -> onBattleEnd(), this);
    }

    /** 当前已装备映射（slotName → equipId，存档用）。 */
    public Map<String, String> getEquippedMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<EquipSlot, Equip> kv : equipped.entrySet())
            map.put(kv.getKey().name(), kv.getValue().id());
        return map;
    }

    /** 读档恢复：按装备ID重新穿戴（走正常 equip 流程重建Buff）。 */
    public void restoreEquipped(Iterable<String> equipIds) {
        for (String id : equipIds) {
            EquipDef def = equipRegistry.tryGet(id);
            if (def != null) equip(new Equip(def));
        }
    }

    // ==================== 装备 / 卸下 ====================

    public EquipResult equip(Equip equip) {
        // 1. 耐久检查
        if (equip.isBroken()) return EquipResult.fail("装备已损坏，需修理");

        // 2. 标签条件检查（条件评估接口）
        if (equip.equipCondition() != null && !equip.equipCondition().evaluate(owner.activeTagIds()))
            return EquipResult.fail("不满足装备条件");

        // 3. 卸下同槽位旧装备
        Equip old = equipped.get(equip.slot());
        if (old != null) unequip(old);

        // 4. 装备 → 以永久Buff形式添加效果（baseStats 转为 StatMod 一并入Buff，保证无状态重建一致）
        equipped.put(equip.slot(), equip);
        List<EffectDef> effects = new ArrayList<>(equip.effects());
        for (Map.Entry<String, Double> kv : equip.baseStats().entrySet())
            effects.add(new StatMod(kv.getKey(), Operator.ADD, kv.getValue().floatValue()));
        owner.buffManager().addBuff(BuffFactory.createFromEquip(equip.id(), equip.name(), effects));

        // 5. 装备标签注入 → 触发 recalculateTags（标签驱动体系）
        if (!equip.tagIds().isEmpty()) {
            owner.equipTagIds().addAll(equip.tagIds());
            owner.recalculateTags();
        }

        // 6. 更新套装计数
        if (equip.setId() != null) {
            activeSetBonuses.merge(equip.setId(), 1, Integer::sum);
            checkSetBonuses();
        }

        return EquipResult.ok(old);
    }

    public EquipResult unequip(Equip equip) {
        if (equipped.get(equip.slot()) != equip) return EquipResult.fail("未装备");

        equipped.remove(equip.slot());
        owner.buffManager().removeBuff(equip.id() + "_buff");

        // 移除装备标签 → 触发 recalculateTags
        if (!equip.tagIds().isEmpty()) {
            owner.equipTagIds().removeAll(equip.tagIds());
            owner.recalculateTags();
        }

        if (equip.setId() != null) {
            int count = activeSetBonuses.getOrDefault(equip.setId(), 0) - 1;
            if (count <= 0) {
                activeSetBonuses.remove(equip.setId());
                removeSetBonus(equip.setId());
            } else {
                activeSetBonuses.put(equip.setId(), count);
            }
            checkSetBonuses();
        }
        return EquipResult.ok(null);
    }

    // ==================== 套装检测 ====================

    /** 套装效果：N件同套装 → 追加Buff（先清旧档再上新档）。 */
    private void checkSetBonuses() {
        for (Map.Entry<String, Integer> kv : activeSetBonuses.entrySet()) {
            SetBonusDef bonus = setRegistry.get(kv.getKey(), kv.getValue());
            if (bonus != null) {
                removeSetBonus(kv.getKey());
                owner.buffManager().addBuff(
                        BuffFactory.createFromSetBonus(bonus.setId(), bonus.bonusName(), bonus.bonusEffects()));
            }
        }
    }

    private void removeSetBonus(String setId) { owner.buffManager().removeBuff(setId + "_bonus"); }

    // ==================== 耐久管理 ====================

    /** 每场战斗后每件装备 -1 耐久；损坏自动卸下并发出事件。 */
    public void onBattleEnd() {
        for (Equip equip : new ArrayList<>(equipped.values())) {
            equip.consumeDurability(1);
            if (equip.isBroken()) {
                unequip(equip);
                eventBus.emit(EventTypes.EQUIP_BROKEN, owner, equip);
            }
        }
    }

    public RepairResult repair(Equip equip, int amount, int goldCost) {
        if (owner.gold() < goldCost) return RepairResult.fail("金币不足");
        if (equip.currentDurability() >= equip.maxDurability()) return RepairResult.fail("不需要修理");

        owner.setGold(owner.gold() - goldCost);
        boolean wasBroken = equip.isBroken();
        equip.repair(amount);
        // 修好后自动重新装备（若未在装备状态）
        if (wasBroken && !equipped.containsKey(equip.slot())) equip(equip);
        return RepairResult.ok();
    }

    // ==================== 查询 ====================

    public Equip get(EquipSlot slot) { return equipped.get(slot); }

    public Map<EquipSlot, Equip> getAllEquipped() { return equipped; }

    public boolean hasItem(String equipId) {
        for (Equip e : equipped.values()) if (e.id().equals(equipId)) return true;
        return false;
    }
}

package com.canglan.world.equipment;

import java.util.List;
import java.util.Map;

import com.canglan.core.effect.EffectDef;
import com.canglan.core.tag.TagCondition;
import com.canglan.data.equipment.EquipDef;
import com.canglan.data.equipment.EquipSlot;

/**
 * Equip — 运行时装备实例。对应 C# Equip。
 * P6 简化：品质骰/词缀系统（EquipQuality/EquipAffix/ForgeSystem）不迁移，
 * 品质倍率恒为 1、词缀加成恒为 0，耐久与标签注入契约完整保留。
 */
public final class Equip {

    private final String id;
    private final String name;
    private final EquipSlot slot;
    private final int tier;
    private final Map<String, Double> baseStats;
    private final List<EffectDef> effects;
    private final TagCondition equipCondition;
    private final int maxDurability;
    private int currentDurability;
    private final String setId;
    private final String upgradePath;
    private final List<String> tagIds;

    public Equip(EquipDef def) {
        this.id = def.id();
        this.name = def.name();
        this.slot = def.slot();
        this.tier = def.tier();
        this.baseStats = def.baseStats();
        this.effects = def.effects();
        this.equipCondition = def.equipCondition();
        this.maxDurability = def.maxDurability();
        this.currentDurability = def.maxDurability();
        this.setId = def.setId();
        this.upgradePath = def.upgradePath();
        this.tagIds = def.tagIds() == null ? List.of() : def.tagIds();
    }

    public String id() { return id; }
    public String name() { return name; }
    public EquipSlot slot() { return slot; }
    public int tier() { return tier; }
    public Map<String, Double> baseStats() { return baseStats; }
    public List<EffectDef> effects() { return effects; }
    public TagCondition equipCondition() { return equipCondition; }
    public int maxDurability() { return maxDurability; }
    public int currentDurability() { return currentDurability; }
    public String setId() { return setId; }
    public String upgradePath() { return upgradePath; }
    public List<String> tagIds() { return tagIds; }

    public boolean isBroken() { return currentDurability <= 0; }
    public boolean isDamaged() { return currentDurability < maxDurability * 0.3; }
    public boolean canUpgrade() { return upgradePath != null; }

    public void consumeDurability(int amount) { currentDurability = Math.max(0, currentDurability - amount); }
    public void repair(int amount) { currentDurability = Math.min(maxDurability, currentDurability + amount); }

    /** 读档恢复耐久。 */
    public void restoreDurability(int value) { currentDurability = Math.max(0, Math.min(maxDurability, value)); }

    @Override
    public String toString() { return name + "(" + id + ") 耐久" + currentDurability + "/" + maxDurability; }
}

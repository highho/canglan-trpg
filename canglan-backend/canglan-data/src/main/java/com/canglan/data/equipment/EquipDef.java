package com.canglan.data.equipment;

import java.util.List;
import java.util.Map;

import com.canglan.core.effect.EffectDef;
import com.canglan.core.tag.TagCondition;

/**
 * EquipDef — 装备配置定义（equipments.json）。对应 C# EquipDef record。
 *
 * @param slot          WEAPON / ARMOR / ACCESSORY / RING
 * @param tier          品质等级 1-5
 * @param baseStats     基础属性加成: { ATK: 18, CRIT: 0.15 }
 * @param effects       复用标签系统的 EffectDef 层次
 * @param equipCondition 穿戴条件（可为 null）
 * @param maxDurability 最大耐久度
 * @param setId         套装ID，null=非套装
 * @param upgradePath   升级目标装备ID，null=不可升级
 * @param tagIds        装备提供的标签（穿戴后注入 ActiveTagIds）
 */
public record EquipDef(
        String id,
        String name,
        EquipSlot slot,
        int tier,
        Map<String, Double> baseStats,
        List<EffectDef> effects,
        TagCondition equipCondition,
        int maxDurability,
        String setId,
        String upgradePath,
        List<String> tagIds) {
}

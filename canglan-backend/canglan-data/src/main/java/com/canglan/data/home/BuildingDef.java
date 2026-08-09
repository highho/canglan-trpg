package com.canglan.data.home;

import java.util.Map;

import com.canglan.core.tag.TagCondition;

/**
 * 建筑蓝图定义（buildings.json）。对应 C# BuildingRegistry 内的定义元组。
 *
 * @param category      STORAGE / CRAFTING / REST / FARM / DEFENSE / UTILITY
 * @param materials     建造材料 { itemId: count }
 * @param condition     建造标签条件，null 语义=AlwaysTrue
 * @param prerequisite  前置建筑ID，null=无
 * @param effectType    效果类型（STORAGE_BONUS/CRAFT_UNLOCK/HEAL_RATE/DEFENSE_BONUS/FARM_YIELD/NPC_ATTRACT），null=无
 * @param effectParams  效果参数
 */
public record BuildingDef(
        String id,
        String name,
        String category,
        Map<String, Integer> materials,
        TagCondition condition,
        String prerequisite,
        String effectType,
        Map<String, Object> effectParams) {
}

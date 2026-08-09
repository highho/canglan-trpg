package com.canglan.data.craft;

import java.util.Map;

import com.canglan.core.tag.TagCondition;

/**
 * 配方定义（recipes.json）。对应 C# Recipe record。
 *
 * @param materials       材料表 { itemId: count }
 * @param unlockCondition 制造技能条件（[锻造Lv1] 等），null 语义=AlwaysTrue
 */
public record Recipe(
        String id,
        String name,
        Map<String, Integer> materials,
        String outputItemId,
        int outputCount,
        TagCondition unlockCondition,
        int craftTime) {
}

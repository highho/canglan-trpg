package com.canglan.core.graph;

import java.util.Map;
import java.util.Set;

/**
 * 职业数据。skillTreeRoot 为技能树入口ID（→ 技能系统）。对应 C# ClassData record。
 *
 * @param name        "魔剑士"
 * @param tagIds      转职获得: ["近战","魔能","黑暗"]
 * @param skillTreeRoot 技能树入口
 * @param statGrowth  每级属性成长: { ATK: 2.5, DEF: 1.0 }
 */
public record ClassData(
        String name,
        Set<String> tagIds,
        String skillTreeRoot,
        Map<String, Double> statGrowth) {
}

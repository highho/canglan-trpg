package com.canglan.core.graph;

import java.util.Map;
import java.util.Set;

/**
 * 种族数据。对应 C# RaceData record。
 *
 * @param name         "天使" / "堕天使"
 * @param tagIds       进化获得: ["神圣","光明"]
 * @param baseStats    { HP: 80, ATK: 12, DEF: 8 }
 * @param conflictTags 进化时需清除的标签: ["神圣","光明"]
 */
public record RaceData(
        String name,
        Set<String> tagIds,
        Map<String, Double> baseStats,
        Set<String> conflictTags) {
}

package com.canglan.world.unit;

import java.util.Map;

/**
 * 行为选项定义。对应 C# BehaviorOption。
 * tagWeights 结构: category(IDENTITY/PERSONALITY/EMOTION...) → {tagId → weightModifier}。
 */
public record BehaviorOption(
        String id,
        String name,
        int baseWeight,
        Map<String, Map<String, Integer>> tagWeights) {

    public BehaviorOption(String id, String name, int baseWeight) {
        this(id, name, baseWeight, Map.of());
    }
}

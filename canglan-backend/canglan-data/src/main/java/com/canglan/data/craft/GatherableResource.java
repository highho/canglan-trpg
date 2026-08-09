package com.canglan.data.craft;

/**
 * 可采集资源（resources.json）。对应 C# GatherableResource。
 * requiredTag 为采集前置标签（如 [采矿]），null=无门槛。
 */
public record GatherableResource(
        String id,
        String name,
        ResourceCategory category,
        int difficulty,
        int yieldPerAction,
        int maxYield,
        String requiredTag) {
}

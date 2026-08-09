package com.canglan.world.craft;

/** 制造结果。对应 C# CraftResult。 */
public record CraftResult(boolean success, String outputItemId, String error) {
}

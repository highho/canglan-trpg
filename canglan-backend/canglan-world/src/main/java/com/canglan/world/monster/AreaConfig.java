package com.canglan.world.monster;

import java.util.List;

import com.canglan.world.BiomeType;

/** 区域配置（生态+等级范围+刷怪条目）。对应 C# AreaConfig。 */
public record AreaConfig(String id, BiomeType biome, int minLevel, int maxLevel, List<SpawnEntry> entries) {
}

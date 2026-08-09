package com.canglan.world;

/** 地形物（采集点/建筑/NPC刷新点/副本入口/家园/怪物刷新点）。对应 C# TerrainFeature。 */
public record TerrainFeature(String id, MapPos pos, FeatureType type) {
}

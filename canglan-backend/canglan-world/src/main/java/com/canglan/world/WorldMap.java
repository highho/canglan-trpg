package com.canglan.world;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * WorldMap — 多层地图（地上/地下/天空），每层独立 FogOfWar。对应 C# WorldMap。
 * 维护地形物列表与生态格子（biome 决定体温/水分消耗）。
 */
public final class WorldMap {

    private final int width;
    private final int height;
    private MapLayer currentLayer = MapLayer.SURFACE;

    private final Map<MapLayer, FogOfWar> layers = new EnumMap<>(MapLayer.class);
    private final List<TerrainFeature> features = new ArrayList<>();
    private final BiomeType[][] biomes;

    public int width() { return width; }
    public int height() { return height; }
    public MapLayer currentLayer() { return currentLayer; }

    public WorldMap(int width, int height) {
        this.width = width;
        this.height = height;
        for (MapLayer layer : MapLayer.values()) {
            layers.put(layer, new FogOfWar(width, height, layer.baseVision()));
        }
        this.biomes = new BiomeType[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                biomes[y][x] = BiomeType.PLAINS;   // 缺省平原
            }
        }
    }

    public void switchLayer(MapLayer layer) { currentLayer = layer; }

    public FogOfWar currentFog() { return layers.get(currentLayer); }

    public FogOfWar getFog(MapLayer layer) { return layers.get(layer); }

    // ==================== 生态 ====================

    public void setBiome(int x, int y, BiomeType biome) {
        if (x >= 0 && x < width && y >= 0 && y < height) biomes[y][x] = biome;
    }

    public BiomeType currentBiome(MapPos pos) {
        return pos != null && pos.x() >= 0 && pos.x() < width && pos.y() >= 0 && pos.y() < height
                ? biomes[pos.y()][pos.x()]
                : BiomeType.PLAINS;
    }

    // ==================== 地形物 ====================

    /** 查找范围内的可交互物。 */
    public List<TerrainFeature> findNearby(MapPos pos, int range) {
        List<TerrainFeature> result = new ArrayList<>();
        for (TerrainFeature f : features) {
            if (f.pos().distanceTo(pos) <= range) result.add(f);
        }
        return result;
    }

    public void addFeature(TerrainFeature feature) { features.add(feature); }

    public void removeFeature(TerrainFeature feature) { features.remove(feature); }

    public List<TerrainFeature> features() { return features; }
}

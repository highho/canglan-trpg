package com.canglan.world;

/** 地形生态（含基础体温）。对应 C# BiomeType。 */
public enum BiomeType {
    PLAINS(80),
    FOREST(70),
    DESERT(40),
    TUNDRA(30),
    SWAMP(60),
    MOUNTAIN(50);

    private final int baseTemperature;

    BiomeType(int baseTemperature) {
        this.baseTemperature = baseTemperature;
    }

    public int baseTemperature() { return baseTemperature; }

    public static BiomeType parse(String raw) {
        if (raw == null) return PLAINS;
        return switch (raw.toUpperCase()) {
            case "PLAINS" -> PLAINS;
            case "FOREST" -> FOREST;
            case "DESERT" -> DESERT;
            case "TUNDRA" -> TUNDRA;
            case "SWAMP" -> SWAMP;
            case "MOUNTAIN" -> MOUNTAIN;
            default -> throw new IllegalArgumentException("未知地形: " + raw);
        };
    }
}

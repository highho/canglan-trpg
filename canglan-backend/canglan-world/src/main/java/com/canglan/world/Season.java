package com.canglan.world;

/** 季节（带基础温度语义）。对应 C# Season。 */
public enum Season {
    SPRING(20),   // 温和
    SUMMER(40),   // 炎热
    AUTUMN(15),   // 凉爽
    WINTER(0);    // 严寒

    private final int baseTemp;

    Season(int baseTemp) {
        this.baseTemp = baseTemp;
    }

    public int baseTemp() { return baseTemp; }

    public static Season parse(String raw) {
        if (raw == null) return SPRING;
        return switch (raw.toUpperCase()) {
            case "SPRING" -> SPRING;
            case "SUMMER" -> SUMMER;
            case "AUTUMN" -> AUTUMN;
            case "WINTER" -> WINTER;
            default -> throw new IllegalArgumentException("未知季节: " + raw);
        };
    }
}

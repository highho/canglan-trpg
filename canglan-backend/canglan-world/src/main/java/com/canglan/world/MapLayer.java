package com.canglan.world;

/** 地图层（每层独立迷雾，枚举值=基础视野格数）。对应 C# MapLayer。 */
public enum MapLayer {
    SURFACE(5, "Surface"),          // 地表 — 默认视野5格
    UNDERGROUND(3, "Underground"),  // 地下 — 视野减半
    SKY(8, "Sky");                  // 天空 — 视野最大

    private final int baseVision;
    private final String saveName;

    MapLayer(int baseVision, String saveName) {
        this.baseVision = baseVision;
        this.saveName = saveName;
    }

    public int baseVision() { return baseVision; }

    /** 存档字符串（对应 C# Enum 名：Surface/Underground/Sky）。 */
    public String saveName() { return saveName; }

    public static MapLayer parse(String raw) {
        if (raw == null) return SURFACE;
        for (MapLayer l : values()) {
            if (l.name().equalsIgnoreCase(raw) || l.saveName.equalsIgnoreCase(raw)) return l;
        }
        throw new IllegalArgumentException("未知地图层: " + raw);
    }
}

package com.canglan.data.craft;

/** 采集资源分类。对应 C# ResourceCategory。 */
public enum ResourceCategory {
    ORE, HERB, WOOD, WATER, PREY, RUIN;

    public static ResourceCategory parse(String raw) {
        if (raw == null) return ORE;
        return switch (raw.toUpperCase()) {
            case "HERB" -> HERB;
            case "WOOD" -> WOOD;
            case "WATER" -> WATER;
            case "PREY" -> PREY;
            case "RUIN" -> RUIN;
            default -> ORE;
        };
    }
}

package com.canglan.world;

/** 难度档位（开拓者式 5 档：休闲/普通/困难/梦魇/深渊）。对应 C# DifficultyMode。 */
public enum DifficultyMode {
    CASUAL,     // 休闲：轻松体验
    NORMAL,     // 普通：标准挑战
    HARD,       // 困难：资源吃紧
    NIGHTMARE,  // 梦魇：步步惊心
    ABYSS;      // 深渊：硬核求生

    public static DifficultyMode parse(String raw) {
        if (raw == null) return NORMAL;
        return switch (raw.toUpperCase()) {
            case "CASUAL" -> CASUAL;
            case "NORMAL" -> NORMAL;
            case "HARD" -> HARD;
            case "NIGHTMARE" -> NIGHTMARE;
            case "ABYSS" -> ABYSS;
            default -> throw new IllegalArgumentException("未知难度: " + raw);
        };
    }
}

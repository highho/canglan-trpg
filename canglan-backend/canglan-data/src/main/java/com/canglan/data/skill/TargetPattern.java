package com.canglan.data.skill;

/** 目标模式（九宫格）。对应 C# TargetPattern。 */
public enum TargetPattern {
    SINGLE, ROW, COLUMN, ALL, SELF, ADJACENT;

    public static TargetPattern parse(String raw) {
        if (raw == null) return SINGLE;
        return switch (raw.toUpperCase()) {
            case "SINGLE" -> SINGLE;
            case "ROW" -> ROW;
            case "COLUMN" -> COLUMN;
            case "ALL" -> ALL;
            case "SELF" -> SELF;
            case "ADJACENT" -> ADJACENT;
            default -> throw new IllegalArgumentException("未知目标模式: " + raw);
        };
    }
}

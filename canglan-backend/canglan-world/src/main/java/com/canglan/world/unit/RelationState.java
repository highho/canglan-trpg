package com.canglan.world.unit;

/** 对玩家的关系状态。对应 C# RelationState。 */
public enum RelationState {
    HOSTILE, NEUTRAL, FRIENDLY, ALLY;

    public static RelationState parse(String raw) {
        if (raw == null) return NEUTRAL;
        return switch (raw.toUpperCase()) {
            case "HOSTILE" -> HOSTILE;
            case "NEUTRAL" -> NEUTRAL;
            case "FRIENDLY" -> FRIENDLY;
            case "ALLY" -> ALLY;
            default -> throw new IllegalArgumentException("未知关系状态: " + raw);
        };
    }
}

package com.canglan.world.unit;

/** 战斗模式：切磋不致死 / 打劫可投降 / 袭杀致死。对应 C# CombatMode。 */
public enum CombatMode {
    NONE, SPAR, ROB, LETHAL;

    public static CombatMode parse(String raw) {
        if (raw == null) return NONE;
        return switch (raw.toUpperCase()) {
            case "NONE" -> NONE;
            case "SPAR" -> SPAR;
            case "ROB" -> ROB;
            case "LETHAL" -> LETHAL;
            default -> throw new IllegalArgumentException("未知战斗模式: " + raw);
        };
    }
}

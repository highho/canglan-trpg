package com.canglan.data.monster;

/** 怪物战斗定位。对应 C# CombatRole。 */
public enum CombatRole {
    MELEE, RANGED, SUPPORT, BOSS;

    public static CombatRole parse(String raw) {
        if (raw == null) return MELEE;
        return switch (raw.toUpperCase()) {
            case "RANGED" -> RANGED;
            case "SUPPORT" -> SUPPORT;
            case "BOSS" -> BOSS;
            default -> MELEE;
        };
    }
}

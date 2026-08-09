package com.canglan.data.skill;

/** 伤害类型。对应 C# DamageType。 */
public enum DamageType {
    PHYSICAL, MAGICAL, TRUE;

    public static DamageType parse(String raw) {
        if (raw == null) return PHYSICAL;
        return switch (raw.toUpperCase()) {
            case "PHYSICAL" -> PHYSICAL;
            case "MAGICAL" -> MAGICAL;
            case "TRUE" -> TRUE;
            default -> PHYSICAL;
        };
    }
}

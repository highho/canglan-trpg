package com.canglan.data.skill;

/** 技能类型。对应 C# SkillType。 */
public enum SkillType {
    ACTIVE, PASSIVE, ULTIMATE;

    public static SkillType parse(String raw) {
        if (raw == null) return ACTIVE;
        return switch (raw.toUpperCase()) {
            case "ACTIVE" -> ACTIVE;
            case "PASSIVE" -> PASSIVE;
            case "ULTIMATE" -> ULTIMATE;
            default -> throw new IllegalArgumentException("未知技能类型: " + raw);
        };
    }
}

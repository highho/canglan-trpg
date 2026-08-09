package com.canglan.world.unit;

/** Unit 角色偏向：Monster/NPC/Ally/Player — 三者是同一张 Unit 表，只是行为池与关系不同。对应 C# UnitRole。 */
public enum UnitRole {
    MONSTER, NPC, ALLY, PLAYER;

    public static UnitRole parse(String raw) {
        if (raw == null) return NPC;
        return switch (raw.toUpperCase()) {
            case "MONSTER" -> MONSTER;
            case "NPC" -> NPC;
            case "ALLY" -> ALLY;
            case "PLAYER" -> PLAYER;
            default -> throw new IllegalArgumentException("未知角色偏向: " + raw);
        };
    }
}

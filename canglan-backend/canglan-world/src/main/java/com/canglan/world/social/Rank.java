package com.canglan.world.social;

import java.util.Locale;

/**
 * 声望等级（含最低声望与折扣）。对应 C# Rank + RankInfo 合并。
 * 铜=100/银=300/金=1000/传说=3000；折扣 5%/10%/20%/30%。
 */
public enum Rank {
    NEUTRAL(0, 0f), BRONZE(100, 0.05f), SILVER(300, 0.10f), GOLD(1000, 0.20f), LEGEND(3000, 0.30f);

    private final int minReputation;
    private final float discount;

    Rank(int minReputation, float discount) {
        this.minReputation = minReputation;
        this.discount = discount;
    }

    public int minReputation() { return minReputation; }
    public float discount() { return discount; }

    public static Rank fromReputation(int rep) {
        Rank result = NEUTRAL;
        for (Rank r : values()) if (rep >= r.minReputation) result = r;
        return result;
    }

    public static Rank fromName(String name) {
        if (name == null) return NEUTRAL;
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "BRONZE", "铜" -> BRONZE;
            case "SILVER", "银" -> SILVER;
            case "GOLD", "金" -> GOLD;
            case "LEGEND", "传说" -> LEGEND;
            default -> NEUTRAL;
        };
    }
}

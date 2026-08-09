package com.canglan.world;

import java.util.List;

/**
 * DifficultySettings — 难度数值配置。对应 C# DifficultySettings record。
 * 影响：敌人属性倍率、生存消耗倍率、金币/经验倍率、死亡惩罚、负重上限、理智消耗。
 */
public record DifficultySettings(
        DifficultyMode mode,
        String name,
        String description,
        double enemyHpMul,      // 敌人生成 HP 倍率
        double enemyAtkMul,     // 敌人攻击倍率
        double enemyExpMul,     // 敌人经验倍率（高难度更多）
        double enemyGoldMul,    // 敌人金币倍率（高难度更多）
        double consumeMul,      // 生存消耗倍率（高难度更快掉）
        double carryMul,        // 负重容量倍率（高难度更小）
        double sanityDrainMul,  // 理智消耗倍率（高难度掉更快）
        int deathPenaltyPct,    // 死亡金币损失百分比
        double dropMul) {       // 掉落倍率（休闲更高）

    public static final List<DifficultySettings> ALL = List.of(
            new DifficultySettings(DifficultyMode.CASUAL, "休闲", "资源充裕，适合体验剧情。",
                    0.8, 0.8, 0.8, 0.8, 0.7, 1.2, 0.5, 10, 1.5),
            new DifficultySettings(DifficultyMode.NORMAL, "普通", "标准 TRPG 挑战。",
                    1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 20, 1.0),
            new DifficultySettings(DifficultyMode.HARD, "困难", "生存消耗加剧，敌人更强。",
                    1.2, 1.2, 1.2, 1.2, 1.3, 0.9, 1.3, 30, 0.8),
            new DifficultySettings(DifficultyMode.NIGHTMARE, "梦魇", "敌人凶悍，资源极缺，步步惊心。",
                    1.5, 1.4, 1.4, 1.4, 1.6, 0.8, 1.6, 50, 0.6),
            new DifficultySettings(DifficultyMode.ABYSS, "深渊", "硬核求生：敌人碾压，容错为零。",
                    1.9, 1.7, 1.7, 1.7, 2.0, 0.7, 2.0, 80, 0.4));

    public static DifficultySettings get(DifficultyMode mode) {
        for (DifficultySettings s : ALL) {
            if (s.mode() == mode) return s;
        }
        return ALL.get(1);
    }
}

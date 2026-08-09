package com.canglan.world.behavior;

import java.util.List;
import java.util.Random;

import com.canglan.core.tag.Tag;
import com.canglan.core.tag.TagCategory;
import com.canglan.world.unit.BehaviorOption;

/**
 * BehaviorEngine — 权重法决策引擎。同一引擎驱动怪物AI和NPC交互。
 * 最终权重 = max(0, 基础权重 + Σ身份修正 + Σ人格修正 + Σ情感修正)。
 * 对应 C# BehaviorEngine（AI 建议版 decideWithAdvice 留待 AI 集成阶段）。
 */
public final class BehaviorEngine {

    private final Random rng;

    public BehaviorEngine(Random rng) {
        this.rng = rng != null ? rng : new Random();
    }

    public BehaviorEngine() { this(null); }

    /** 决策：返回权重最高的选项（全部为0时返回第一项）。 */
    public BehaviorOption decide(List<Tag> tags, List<BehaviorOption> options) {
        if (options == null || options.isEmpty()) return null;
        BehaviorOption best = null;
        int bestWeight = Integer.MIN_VALUE;
        for (BehaviorOption opt : options) {
            int weight = computeWeight(opt, tags);
            if (weight > bestWeight) {
                bestWeight = weight;
                best = opt;
            }
        }
        return best != null ? best : options.get(0);
    }

    /** 计算单个选项的最终权重 = max(0, 基础权重 + 标签修正之和)。 */
    public int computeWeight(BehaviorOption option, List<Tag> tags) {
        int weight = option.baseWeight();
        for (Tag tag : tags) {
            String categoryKey = categoryKey(tag.category());
            if (option.tagWeights() != null) {
                var catWeights = option.tagWeights().get(categoryKey);
                if (catWeights != null) {
                    Integer mod = catWeights.get(tag.id());
                    if (mod != null) weight += mod;
                }
            }
        }
        return Math.max(0, weight);
    }

    /** 按权重随机抽取（权重越高概率越大；用于背刺判定等概率场景）。 */
    public BehaviorOption rollWeighted(List<Tag> tags, List<BehaviorOption> options) {
        if (options == null || options.isEmpty()) return null;
        int[] weights = new int[options.size()];
        int total = 0;
        for (int i = 0; i < options.size(); i++) {
            weights[i] = computeWeight(options.get(i), tags);
            total += weights[i];
        }
        if (total <= 0) return options.get(rng.nextInt(options.size()));
        int roll = rng.nextInt(total);
        for (int i = 0; i < options.size(); i++) {
            roll -= weights[i];
            if (roll < 0) return options.get(i);
        }
        return options.get(options.size() - 1);
    }

    /** TagCategory → tagWeights 字典键（IDENTITY/PERSONALITY/EMOTION/...）。 */
    public static String categoryKey(TagCategory category) {
        return switch (category) {
            case ELEMENT -> "ELEMENT";
            case IDENTITY -> "IDENTITY";
            case PERSONALITY -> "PERSONALITY";
            case EMOTION -> "EMOTION";
            case QUEST_MARK -> "QUEST_MARK";
            case SKILL -> "SKILL";
        };
    }
}

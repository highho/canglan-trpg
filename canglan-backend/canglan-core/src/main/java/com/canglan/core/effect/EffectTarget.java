package com.canglan.core.effect;

import java.util.Set;

/**
 * 效果作用目标的最小抽象。解耦 core 与具体 Unit：
 * DamageMod/TriggerDef 依赖此接口而非 Unit；将来 Unit 实现本接口。
 */
public interface EffectTarget {
    boolean hasTag(String tagId);
    int gridRow();
    Set<String> activeTagIds();
}

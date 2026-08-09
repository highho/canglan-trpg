package com.canglan.data.buff;

import java.util.List;

import com.canglan.core.effect.EffectDef;

/**
 * BuffDef — Buff 配置定义（复用标签系统的 EffectDef 层次）。对应 C# BuffDef record。
 *
 * @param defaultDuration -1 = 永久
 * @param stackable       同名Buff是否可叠加
 * @param maxStacks       最大叠加层数，1=不可叠加
 */
public record BuffDef(
        String id,
        String name,
        BuffType type,
        int defaultDuration,
        List<EffectDef> effects,
        boolean stackable,
        int maxStacks) {
}

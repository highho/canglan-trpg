package com.canglan.core.tag;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.core.effect.EffectDef;

/**
 * 设计契约层标签定义。effects 在加载时由 EffectParser 解析为强类型列表；
 * behaviorWeights 结构: pool(combat/social) → {optionId → weight}。
 * 对应 C# TagDef record。
 */
public record TagDef(
        String id,
        String name,
        String description,
        TagCategory category,
        int tier,
        Set<TagSource> allowedSources,
        List<EffectDef> effects,
        Map<String, Map<String, Integer>> behaviorWeights) {

    public boolean isAllowedFrom(TagSource source) {
        return allowedSources.contains(source);
    }
}

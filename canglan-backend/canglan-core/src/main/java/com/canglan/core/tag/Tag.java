package com.canglan.core.tag;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.core.effect.EffectDef;

/**
 * Tag — 运行时标签实例（由 TagDef 构造）。对应 C# Tag 类。
 * behaviorWeights 结构: pool(COMBAT/SOCIAL) → {optionId → weight}。
 */
public final class Tag {

    private final String id;
    private final String name;
    private final String description;
    private final TagCategory category;
    private final int tier;
    private final Set<TagSource> allowedSources;
    private final List<EffectDef> effects;
    private final Map<String, Map<String, Integer>> behaviorWeights;

    public Tag(TagDef def) {
        this.id = def.id();
        this.name = def.name();
        this.description = def.description();
        this.category = def.category();
        this.tier = def.tier();
        this.allowedSources = def.allowedSources();
        this.effects = def.effects();
        this.behaviorWeights = def.behaviorWeights();
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public TagCategory category() { return category; }
    public int tier() { return tier; }
    public Set<TagSource> allowedSources() { return allowedSources; }
    public List<EffectDef> effects() { return effects; }
    public Map<String, Map<String, Integer>> behaviorWeights() { return behaviorWeights; }

    public boolean isAllowedFrom(TagSource source) { return allowedSources.contains(source); }

    @Override
    public String toString() { return name + "(" + id + ")"; }
}

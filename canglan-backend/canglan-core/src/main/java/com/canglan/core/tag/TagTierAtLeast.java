package com.canglan.core.tag;

import java.util.Set;

/**
 * 持有指定标签且 tier 达到 minTier。对应 C# TagTierAtLeast。
 * 通过 TagRegistryHolder 访问注册表（含 [适性] TierReduction 涌现减免）。
 */
public record TagTierAtLeast(String tagId, int minTier) implements TagCondition {
    @Override
    public boolean evaluate(Set<String> tagIds) {
        if (!tagIds.contains(tagId)) return false;
        TagRegistry registry = TagRegistryHolder.current();
        if (registry == null) return true;
        int required = minTier - registry.getTierReduction();
        TagDef def = registry.tryGet(tagId);
        return def != null && def.tier() >= required;
    }
    @Override
    public String toString() { return "TierAtLeast(" + tagId + "," + minTier + ")"; }
}

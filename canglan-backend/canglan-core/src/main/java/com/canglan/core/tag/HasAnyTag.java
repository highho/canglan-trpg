package com.canglan.core.tag;

import java.util.Set;

/** 拥有任一指定标签。对应 C# HasAnyTag。 */
public record HasAnyTag(Set<String> candidates) implements TagCondition {
    @Override
    public boolean evaluate(Set<String> tagIds) {
        for (String c : candidates) {
            if (tagIds.contains(c)) return true;
        }
        return false;
    }
    @Override
    public String toString() { return "HasAnyTag(" + candidates + ")"; }
}

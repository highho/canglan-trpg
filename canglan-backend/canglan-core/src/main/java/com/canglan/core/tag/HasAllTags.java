package com.canglan.core.tag;

import java.util.Set;

/** 拥有全部指定标签。对应 C# HasAllTags。 */
public record HasAllTags(Set<String> required) implements TagCondition {
    @Override
    public boolean evaluate(Set<String> tagIds) {
        return tagIds.containsAll(required);
    }
    @Override
    public String toString() { return "HasAllTags(" + required + ")"; }
}

package com.canglan.core.tag;

import java.util.Set;

/** 拥有某标签。对应 C# HasTag。 */
public record HasTag(String tagId) implements TagCondition {
    @Override
    public boolean evaluate(Set<String> tagIds) {
        return tagIds.contains(tagId);
    }
    @Override
    public String toString() { return "HasTag(" + tagId + ")"; }
}

package com.canglan.core.tag;

import java.util.Set;

/** 条件组合：NOT。对应 C# NotCondition。 */
public record NotCondition(TagCondition condition) implements TagCondition {
    @Override
    public boolean evaluate(Set<String> tagIds) {
        return !condition.evaluate(tagIds);
    }
}

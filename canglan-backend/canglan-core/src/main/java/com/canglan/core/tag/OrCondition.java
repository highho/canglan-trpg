package com.canglan.core.tag;

import java.util.List;
import java.util.Set;

/** 条件组合：OR。对应 C# OrCondition。 */
public record OrCondition(List<TagCondition> conditions) implements TagCondition {
    @Override
    public boolean evaluate(Set<String> tagIds) {
        for (TagCondition c : conditions) {
            if (c.evaluate(tagIds)) return true;
        }
        return false;
    }
}

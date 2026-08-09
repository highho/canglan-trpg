package com.canglan.core.tag;

import java.util.Set;

/** 恒真条件（对话树默认分支用）。对应 C# AlwaysTrue。 */
public record AlwaysTrue() implements TagCondition {
    @Override
    public boolean evaluate(Set<String> tagIds) {
        return true;
    }
}

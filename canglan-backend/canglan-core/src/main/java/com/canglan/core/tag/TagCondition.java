package com.canglan.core.tag;

import java.util.Set;

/**
 * 统一条件评估接口：驱动种族进化/职业转职/任务触发/NPC对话分支/技能解锁。
 * 对应 C# ITagCondition。
 */
public interface TagCondition {
    boolean evaluate(Set<String> tagIds);
}

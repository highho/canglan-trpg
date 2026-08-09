package com.canglan.core.graph;

import java.util.Map;
import java.util.Set;

import com.canglan.core.tag.TagCondition;

/**
 * 任务数据。对应 C# QuestData record。
 *
 * @param name            "S级:屠龙"
 * @param description     描述
 * @param acceptCondition 接受条件（与边条件独立，允许更严格），可为 null
 * @param minLevel        最低等级
 * @param cooldown        可重复任务冷却回合数，0=一次性
 * @param rewardTagIds    完成后获得的 QUEST_MARK 标签
 * @param rewards         { gold: 5000, "屠龙者徽章": 1 }
 */
public record QuestData(
        String name,
        String description,
        TagCondition acceptCondition,
        int minLevel,
        int cooldown,
        Set<String> rewardTagIds,
        Map<String, Integer> rewards) {
}

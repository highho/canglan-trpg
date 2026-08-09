package com.canglan.core.graph;

import java.util.Set;

import com.canglan.core.tag.TagCondition;

/** 任务节点（任务图 = 有向图允许环，支持多前置/分支汇合/可重复）。对应 C# QuestNode。 */
public final class QuestNode extends GraphNode<QuestData> {

    public QuestNode(String id, QuestData data) {
        super(id, data);
    }

    public String name() { return data().name(); }

    public boolean isRepeatable() { return data().cooldown() > 0; }

    /** 接受检查：等级达标 + 任一入边条件满足（无入边则用 acceptCondition，缺省 true）。 */
    public boolean canAccept(Set<String> tagIds, int playerLevel) {
        if (data().minLevel() > playerLevel) return false;
        if (incomingEdges().isEmpty()) {
            TagCondition accept = data().acceptCondition();
            return accept == null || accept.evaluate(tagIds);
        }
        for (Edge<QuestData> edge : incomingEdges()) {
            if (edge.condition() == null || edge.condition().evaluate(tagIds)) return true;
        }
        return false;
    }
}

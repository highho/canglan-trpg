package com.canglan.core.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.canglan.core.tag.EvalContext;
import com.canglan.core.tag.TagCondition;

/**
 * 完整边定义：标签条件 + 额外需求（等级/金币/道具）+ 可逆标记。对应 C# Edge&lt;T&gt;。
 */
public final class Edge<T> {
    private GraphNode<T> from;
    private GraphNode<T> to;
    private final TagCondition condition;
    private final Map<String, Object> requirements;
    private final boolean bidirectional;
    private final String description;

    public Edge(GraphNode<T> from, GraphNode<T> to, TagCondition condition,
                Map<String, Object> requirements, boolean bidirectional, String description) {
        this.from = from;
        this.to = to;
        this.condition = condition;
        this.requirements = requirements == null ? new HashMap<>() : requirements;
        this.bidirectional = bidirectional;
        this.description = description;
    }

    public GraphNode<T> from() { return from; }
    public GraphNode<T> to() { return to; }
    public TagCondition condition() { return condition; }
    public Map<String, Object> requirements() { return requirements; }
    public boolean bidirectional() { return bidirectional; }
    public String description() { return description; }

    void setFrom(GraphNode<T> from) { this.from = from; }
    void setTo(GraphNode<T> to) { this.to = to; }

    public boolean isAvailable(Set<String> tagIds, EvalContext ctx) {
        if (condition != null && !condition.evaluate(tagIds)) return false;
        return meetsRequirements(ctx);
    }

    private boolean meetsRequirements(EvalContext ctx) {
        if (requirements.isEmpty()) return true;
        if (ctx == null) return false;
        Object level = requirements.get("level");
        if (level != null && ctx.getExtraInt("level", 0) < toInt(level)) return false;
        Object gold = requirements.get("gold");
        if (gold != null && ctx.getExtraInt("gold", 0) < toInt(gold)) return false;
        for (Map.Entry<String, Object> kv : requirements.entrySet()) {
            if (kv.getKey().equals("level") || kv.getKey().equals("gold")) continue;
            if (ctx.getExtraInt(kv.getKey(), 0) < toInt(kv.getValue())) return false;
        }
        return true;
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }
}

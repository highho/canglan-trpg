package com.canglan.core.tag;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 条件评估上下文：当条件需要更多运行时信息时使用（如等级/金币/NPC 匹配）。
 * 对应 C# EvalContext。player/npc 用 Object 占位，避免 core 依赖具体 Unit。
 */
public final class EvalContext {
    private final Set<String> unitTagIds;
    private final Map<String, Object> extra;
    private final Object player;
    private final Object npc;

    public EvalContext(Set<String> unitTagIds, Map<String, Object> extra, Object player, Object npc) {
        this.unitTagIds = unitTagIds;
        this.extra = extra == null ? new HashMap<>() : extra;
        this.player = player;
        this.npc = npc;
    }

    public static EvalContext of(Set<String> unitTagIds) {
        return new EvalContext(unitTagIds, null, null, null);
    }

    public Set<String> unitTagIds() { return unitTagIds; }
    public Object player() { return player; }
    public Object npc() { return npc; }

    @SuppressWarnings("unchecked")
    public <T> T getExtra(String key) {
        Object v = extra.get(key);
        return (T) v;
    }

    public int getExtraInt(String key, int fallback) {
        Object v = extra.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }
}

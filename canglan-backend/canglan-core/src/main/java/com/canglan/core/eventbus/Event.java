package com.canglan.core.eventbus;

import java.util.HashMap;
import java.util.Map;

/**
 * 事件载体。对应 C# 的 Event record。
 * payload 为泛型 Map，避免 EventBus 模块依赖具体领域类型（Unit 等）。
 */
public record Event(String type, Object source, Map<String, Object> payload) {

    public Event {
        payload = payload == null ? new HashMap<>() : payload;
    }

    /** 按 key 取负载并转型；不存在或类型不符返回 null。 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Object v = payload.get(key);
        return (T) v;
    }

    public Object getRaw(String key) {
        return payload.get(key);
    }

    public int getInt(String key, int fallback) {
        Object v = payload.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }
}

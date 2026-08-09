package com.canglan.core.eventbus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * EventBus 完整实现（同步发射 + 惰性清理）。对应 C# EventBusImpl。
 * 保持同步语义：emit 在当前线程依次回调所有订阅者。
 */
public final class EventBusImpl implements EventBus {

    private final Map<String, List<Subscription>> byType = new ConcurrentHashMap<>();
    private final Map<Object, List<Subscription>> byOwner = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    @Override
    public void emit(String eventType, Object... payload) {
        emitEvent(buildEvent(eventType, payload));
    }

    @Override
    public void emitEvent(Event evt) {
        List<Subscription> snapshot;
        synchronized (lock) {
            List<Subscription> stored = byType.get(evt.type());
            if (stored == null || stored.isEmpty()) return;
            snapshot = new ArrayList<>(stored);
        }
        for (Subscription sub : snapshot) sub.invoke(evt);
    }

    @Override
    public Subscription subscribe(String eventType, Consumer<Event> callback) {
        return subscribeWithOwner(eventType, callback, null);
    }

    @Override
    public Subscription subscribeWithOwner(String eventType, Consumer<Event> callback, Object owner) {
        Subscription sub = new Subscription(eventType, callback, owner);
        synchronized (lock) {
            byType.computeIfAbsent(eventType, k -> new ArrayList<>()).add(sub);
            if (owner != null) {
                byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(sub);
            }
        }
        return sub;
    }

    @Override
    public void unsubscribeAll(Object owner) {
        List<Subscription> subs;
        synchronized (lock) {
            subs = byOwner.remove(owner);
            if (subs == null) return;
            for (Subscription s : subs) s.deactivate();
        }
    }

    @Override
    public void unsubscribe(Subscription sub) {
        if (sub != null) sub.deactivate();
    }

    /**
     * 智能 payload 映射：按参数类型自动填入 key（与 C# BuildEvent 一致）。
     * 首个对象 → target/source/unit，数字 → amount，字符串 → text，其余 → data。
     */
    private static Event buildEvent(String type, Object[] payload) {
        Map<String, Object> map = new HashMap<>();
        boolean targetSet = false, sourceSet = false;
        if (payload != null) {
            for (Object obj : payload) {
                if (obj == null) continue;
                if (obj instanceof Number) {
                    map.put("amount", obj);
                } else if (obj instanceof String s) {
                    map.put("text", s);
                } else {
                    // 领域对象（Unit 等）：按出现顺序填 target/source/unit
                    if (!targetSet) { map.put("target", obj); targetSet = true; }
                    else if (!sourceSet) { map.put("source", obj); sourceSet = true; }
                    else map.put("unit", obj);
                }
            }
        }
        Object source = (payload != null && payload.length > 0) ? payload[0] : null;
        return new Event(type, source, map);
    }
}

package com.canglan.core.eventbus;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 订阅包装。owner 用于按属主批量清理（recalculateTags 时 unsubscribeAll）。
 * 对应 C# Subscription。
 */
public final class Subscription {
    private final String id = UUID.randomUUID().toString().replace("-", "");
    private final String eventType;
    private final Consumer<Event> callback;
    private final Object owner;
    private volatile boolean active = true;

    public Subscription(String eventType, Consumer<Event> callback, Object owner) {
        this.eventType = eventType;
        this.callback = callback;
        this.owner = owner;
    }

    public String getId() { return id; }
    public String getEventType() { return eventType; }
    public Object getOwner() { return owner; }
    public boolean isActive() { return active; }

    void deactivate() { this.active = false; }

    public void invoke(Event e) {
        if (active) callback.accept(e);
    }
}

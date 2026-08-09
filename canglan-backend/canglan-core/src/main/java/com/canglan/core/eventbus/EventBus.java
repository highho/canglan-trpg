package com.canglan.core.eventbus;

import java.util.function.Consumer;

/** EventBus 核心接口：发布/订阅/按属主清理。对应 C# IEventBus。 */
public interface EventBus {

    /** 发射事件（智能 payload 映射）。 */
    void emit(String eventType, Object... payload);

    /** 发射预构造事件。 */
    void emitEvent(Event evt);

    Subscription subscribe(String eventType, Consumer<Event> callback);

    Subscription subscribeWithOwner(String eventType, Consumer<Event> callback, Object owner);

    /** 按属主批量失效订阅。 */
    void unsubscribeAll(Object owner);

    void unsubscribe(Subscription sub);
}

package com.canglan.world.unit;

import java.util.HashMap;
import java.util.Map;

import com.canglan.core.eventbus.Event;
import com.canglan.core.eventbus.EventTypes;

/** 预定义的死亡事件构造器。对应 C# DeathEvent。 */
public final class DeathEvent {

    private DeathEvent() {}

    public static Event of(Unit dead, Unit killer) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deadUnit", dead);
        payload.put("killer", killer);
        return new Event(EventTypes.UNIT_DEATH, killer, payload);
    }
}

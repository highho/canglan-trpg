package com.canglan.world.npc.dialogue;

import java.util.HashMap;
import java.util.Map;

import com.canglan.world.unit.Unit;

/** 设置任务旗标（存 metadata["questFlags"]）。对应 C# SetQuestFlag。 */
public record SetQuestFlag(String flagId, boolean value) implements DialogueAction {
    @Override
    @SuppressWarnings("unchecked")
    public void execute(Unit player, Unit npc) {
        Map<String, Boolean> flags = (Map<String, Boolean>) player.metadata().get("questFlags");
        if (flags == null) player.metadata().put("questFlags", flags = new HashMap<>());
        flags.put(flagId, value);
    }
}

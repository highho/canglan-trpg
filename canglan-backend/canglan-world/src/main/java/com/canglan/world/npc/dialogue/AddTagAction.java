package com.canglan.world.npc.dialogue;

import com.canglan.world.unit.Unit;

/** 给玩家添加任务标签（不可逆来源）。对应 C# AddTag。 */
public record AddTagAction(String tagId) implements DialogueAction {
    @Override
    public void execute(Unit player, Unit npc) {
        player.questTagIds().add(tagId);
        player.recalculateTags();
    }
}

package com.canglan.world.npc.dialogue;

import com.canglan.world.unit.Unit;

/** 移除玩家任务标签。对应 C# RemoveTag。 */
public record RemoveTagAction(String tagId) implements DialogueAction {
    @Override
    public void execute(Unit player, Unit npc) {
        player.questTagIds().remove(tagId);
        player.recalculateTags();
    }
}

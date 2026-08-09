package com.canglan.world.npc.dialogue;

import com.canglan.world.unit.Unit;

/** 给予物品（NPC → 玩家）。对应 C# GiveItem。 */
public record GiveItemAction(String itemId, int quantity) implements DialogueAction {
    @Override
    public void execute(Unit player, Unit npc) {
        npc.inventory().remove(itemId, quantity);
        player.inventory().add(itemId, quantity);
    }
}

package com.canglan.world.npc.dialogue;

import com.canglan.world.unit.Unit;

/** 好感度调整。对应 C# ChangeAffinity。 */
public record ChangeAffinity(int amount) implements DialogueAction {
    @Override
    public void execute(Unit player, Unit npc) { npc.addAffinity(amount); }
}

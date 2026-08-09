package com.canglan.world.npc.dialogue;

import com.canglan.world.unit.CombatMode;
import com.canglan.world.unit.Unit;

/** NPC 切换战斗模式（切磋/打劫/袭杀）。对应 C# EnterCombatMode。 */
public record EnterCombatAction(CombatMode mode) implements DialogueAction {
    @Override
    public void execute(Unit player, Unit npc) { npc.enterCombat(mode); }
}

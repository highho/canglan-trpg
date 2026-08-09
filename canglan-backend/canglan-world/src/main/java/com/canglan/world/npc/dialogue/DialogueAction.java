package com.canglan.world.npc.dialogue;

import com.canglan.world.unit.Unit;

/**
 * DialogueAction — 对话操作接口。对应 C# IDialogueAction。
 * 实现：ChangeAffinity / GiveItemAction / SetQuestFlag / AddTagAction / RemoveTagAction / EnterCombatAction。
 */
public interface DialogueAction {
    void execute(Unit player, Unit npc);
}

package com.canglan.world.npc.dialogue;

import java.util.List;

import com.canglan.core.tag.TagCondition;

/** 对话分支：条件 + 文本 + 下一节点 + 触发操作。对应 C# DialogueBranch。 */
public record DialogueBranch(
        TagCondition condition,
        String text,
        String nextNodeId,
        List<DialogueAction> actions) {
}

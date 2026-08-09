package com.canglan.world.npc.dialogue;

import java.util.ArrayList;
import java.util.List;

import com.canglan.core.tag.AlwaysTrue;
import com.canglan.core.tag.EvalContext;

/**
 * DialogueNode — 对话节点。基于双方标签匹配选择分支（第一个满足条件的）。
 * 对应 C# DialogueNode。
 */
public final class DialogueNode {

    private final String id;
    private final String text;
    private final List<DialogueBranch> branches = new ArrayList<>();
    private final List<DialogueAction> onEnterActions = new ArrayList<>();
    private boolean isExit;

    public DialogueNode(String id, String text) {
        this.id = id;
        this.text = text;
    }

    public String id() { return id; }
    public String text() { return text; }
    public List<DialogueBranch> branches() { return branches; }
    public List<DialogueAction> onEnterActions() { return onEnterActions; }
    public boolean isExit() { return isExit; }
    public void setExit(boolean exit) { this.isExit = exit; }

    /** 根据上下文选择最佳分支（第一个满足条件的）。 */
    public DialogueBranch selectBranch(EvalContext ctx) {
        for (DialogueBranch b : branches)
            if (b.condition().evaluate(ctx.unitTagIds())) return b;
        return null;
    }

    /** 无条件的默认分支。 */
    public DialogueBranch defaultBranch() {
        for (DialogueBranch b : branches)
            if (b.condition() instanceof AlwaysTrue) return b;
        return null;
    }
}

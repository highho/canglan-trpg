package com.canglan.world.npc.dialogue;

import java.util.Map;

import com.canglan.core.tag.EvalContext;
import com.canglan.world.unit.Unit;

/**
 * DialogueTree — 对话树。条件分支遍历，选择分支时执行操作。对应 C# DialogueTree。
 */
public final class DialogueTree {

    private final Map<String, DialogueNode> nodesById;
    private final String rootNodeId;

    public DialogueTree(String rootNodeId, Map<String, DialogueNode> nodes) {
        this.rootNodeId = rootNodeId;
        this.nodesById = nodes;
    }

    /** 从根节点开始遍历。 */
    public DialogueNode getRoot() { return nodesById.get(rootNodeId); }

    /** 根据触发器选择对话入口（无匹配回退根节点）。 */
    public DialogueNode selectTrigger(String trigger, Unit context) {
        DialogueNode n = nodesById.get(trigger);
        return n != null ? n : getRoot();
    }

    public DialogueNode getNode(String id) { return nodesById.get(id); }

    /** 推进对话：选择分支 → 执行操作 → 返回下一节点（null=对话结束）。 */
    public DialogueNode next(DialogueNode current, EvalContext ctx) {
        DialogueBranch branch = current.selectBranch(ctx);
        if (branch == null) branch = current.defaultBranch();
        if (branch == null || branch.nextNodeId() == null) return null;
        for (DialogueAction action : branch.actions())
            action.execute((Unit) ctx.player(), (Unit) ctx.npc());
        return getNode(branch.nextNodeId());
    }
}

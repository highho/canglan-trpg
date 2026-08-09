package com.canglan.world.npc.dialogue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.canglan.core.json.JsonValue;
import com.canglan.core.tag.AlwaysTrue;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;
import com.canglan.world.unit.CombatMode;

/** 对话树加载器（从 npcs.json 的 dialogueTree 节解析）。对应 C# DialogueTreeLoader。 */
public final class DialogueTreeLoader {

    private final TagConditionParser parser;

    public DialogueTreeLoader(TagConditionParser parser) {
        this.parser = parser;
    }

    public DialogueTree load(JsonValue treeNode) {
        if (treeNode == null || !treeNode.isObject()) return null;
        Map<String, DialogueNode> nodes = new LinkedHashMap<>();
        String rootId = null;

        for (Map.Entry<String, JsonValue> prop : treeNode.asObject().entrySet()) {
            JsonValue n = prop.getValue();
            DialogueNode node = new DialogueNode(prop.getKey(), n.getString("text", ""));
            node.setExit(n.getBoolean("isExit", false));

            JsonValue enters = n.get("onEnter");
            if (enters != null && enters.isArray())
                for (JsonValue a : enters.asArray())
                    node.onEnterActions().add(parseAction(a));

            JsonValue branches = n.get("branches");
            if (branches != null && branches.isArray()) {
                for (JsonValue b : branches.asArray()) {
                    String condText = b.getString("condition", "");
                    TagCondition condition = condText.isEmpty() ? new AlwaysTrue() : parser.parse(condText);
                    List<DialogueAction> actions = new ArrayList<>();
                    JsonValue acts = b.get("actions");
                    if (acts != null && acts.isArray())
                        for (JsonValue a : acts.asArray()) actions.add(parseAction(a));
                    node.branches().add(new DialogueBranch(
                            condition,
                            b.getString("text", ""),
                            b.has("next") ? b.getString("next", null) : null,
                            actions));
                }
            }

            if (rootId == null) rootId = prop.getKey();   // 第一个节点为根
            if (n.getBoolean("root", false)) rootId = prop.getKey();
            nodes.put(prop.getKey(), node);
        }
        return rootId == null ? null : new DialogueTree(rootId, nodes);
    }

    private DialogueAction parseAction(JsonValue a) {
        String type = a.getString("type", "");
        return switch (type) {
            case "affinity" -> new ChangeAffinity(a.getInt("amount", 0));
            case "giveItem" -> new GiveItemAction(a.getString("itemId", ""), a.getInt("count", 1));
            case "questFlag" -> new SetQuestFlag(a.getString("flag", ""), a.getBoolean("value", true));
            case "addTag" -> new AddTagAction(a.getString("tag", ""));
            case "removeTag" -> new RemoveTagAction(a.getString("tag", ""));
            case "enterCombat" -> new EnterCombatAction(parseMode(a.getString("mode", "SPAR")));
            default -> new ChangeAffinity(0);
        };
    }

    private static CombatMode parseMode(String raw) {
        return switch (raw == null ? "SPAR" : raw.toUpperCase()) {
            case "ROB" -> CombatMode.ROB;
            case "LETHAL" -> CombatMode.LETHAL;
            default -> CombatMode.SPAR;
        };
    }
}

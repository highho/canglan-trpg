package com.canglan.data.skill;

import java.util.LinkedHashMap;
import java.util.Map;

import com.canglan.core.graph.GraphEngine;
import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;

/**
 * 技能树注册表（skillTrees.json）：{ treeId: { nodes:[skillId], edges:[{from,to,condition}] } }。
 * 对应 C# SkillTreeRegistry。
 */
public final class SkillTreeRegistry {

    private final Map<String, SkillTree> treesById = new LinkedHashMap<>();
    private final TagConditionParser conditionParser;
    private final SkillRegistry skillRegistry;

    public SkillTreeRegistry(TagConditionParser conditionParser, SkillRegistry skillRegistry) {
        this.conditionParser = conditionParser;
        this.skillRegistry = skillRegistry;
    }

    public void loadFromText(String json) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> treeEntry : root.asObject().entrySet()) {
            GraphEngine<SkillData> graph = new GraphEngine<>(conditionParser);
            JsonValue tree = treeEntry.getValue();

            JsonValue nodes = tree.get("nodes");
            if (nodes != null && nodes.isArray()) {
                for (JsonValue n : nodes.asArray()) {
                    String skillId = n.asString();
                    graph.addNode(new SkillData.SkillNode(skillId, new SkillData(skillRegistry.get(skillId))));
                }
            }
            JsonValue edges = tree.get("edges");
            if (edges != null && edges.isArray()) {
                for (JsonValue e : edges.asArray()) {
                    JsonValue c = e.get("condition");
                    TagCondition condition = (c != null && c.isString()) ? conditionParser.parse(c.asString()) : null;
                    graph.connect(e.getString("from", null), e.getString("to", null), condition, null, false, null);
                }
            }
            register(new SkillTree(treeEntry.getKey(), graph));
        }
    }

    public void register(SkillTree tree) { treesById.put(tree.id(), tree); }

    public SkillTree get(String id) {
        SkillTree t = treesById.get(id);
        if (t == null) throw new IllegalArgumentException("未知技能树: " + id);
        return t;
    }

    public SkillTree tryGet(String id) { return treesById.get(id); }
}

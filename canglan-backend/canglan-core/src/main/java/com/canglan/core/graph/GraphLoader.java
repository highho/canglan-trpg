package com.canglan.core.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;
import com.canglan.core.tag.TagRegistry;

/**
 * 三图加载器。解析 JSON → 创建节点 → 解析边条件 → 连接（两遍法）。
 * 对应 C# GraphLoader。
 */
public final class GraphLoader {

    private final TagConditionParser parser;
    private final TagRegistry registry;

    public GraphLoader(TagConditionParser parser, TagRegistry registry) {
        this.parser = parser;
        this.registry = registry;
    }

    public TagRegistry registry() { return registry; }

    // ==================== 种族进化图 ====================

    public GraphEngine<RaceData> loadRaceGraphFromText(String json) {
        GraphEngine<RaceData> engine = new GraphEngine<>(parser);
        JsonValue root = JsonReader.parse(json);

        // 第一遍：创建全部节点
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            JsonValue node = entry.getValue();
            RaceData data = new RaceData(
                    node.getString("name", entry.getKey()),
                    parseStringSet(node, "tagIds"),
                    parseFloatMap(node, "baseStats"),
                    parseStringSet(node, "conflictTags"));
            engine.addNode(new RaceNode(entry.getKey(), data));
        }

        // 第二遍：连接边
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            connectEdges(engine, entry.getKey(), entry.getValue());
        }
        return engine;
    }

    // ==================== 职业转职图 ====================

    public GraphEngine<ClassData> loadClassGraphFromText(String json) {
        GraphEngine<ClassData> engine = new GraphEngine<>(parser);
        JsonValue root = JsonReader.parse(json);

        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            JsonValue node = entry.getValue();
            ClassData data = new ClassData(
                    node.getString("name", entry.getKey()),
                    parseStringSet(node, "tagIds"),
                    node.getString("skillTreeRoot", null),
                    parseFloatMap(node, "statGrowth"));
            engine.addNode(new ClassNode(entry.getKey(), data));
        }

        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            connectEdges(engine, entry.getKey(), entry.getValue());
        }
        return engine;
    }

    // ==================== 任务图 ====================

    public GraphEngine<QuestData> loadQuestGraphFromText(String json) {
        GraphEngine<QuestData> engine = new GraphEngine<>(parser);
        JsonValue root = JsonReader.parse(json);

        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            JsonValue node = entry.getValue();
            JsonValue ac = node.get("acceptCondition");
            TagCondition accept = (ac != null && ac.isString()) ? parser.parse(ac.asString()) : null;
            Map<String, Integer> rewards = new HashMap<>();
            JsonValue rw = node.get("rewards");
            if (rw != null && rw.isObject()) {
                for (Map.Entry<String, JsonValue> f : rw.asObject().entrySet()) {
                    rewards.put(f.getKey(), f.getValue().isNumber() ? f.getValue().asInt() : 1);
                }
            }
            QuestData data = new QuestData(
                    node.getString("name", entry.getKey()),
                    node.getString("description", ""),
                    accept,
                    node.getInt("minLevel", 0),
                    node.getInt("cooldown", 0),
                    parseStringSet(node, "rewardTagIds"),
                    rewards);
            engine.addNode(new QuestNode(entry.getKey(), data));
        }

        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            connectEdges(engine, entry.getKey(), entry.getValue());
        }
        return engine;
    }

    // ==================== 公共 ====================

    private <T> void connectEdges(GraphEngine<T> engine, String fromId, JsonValue node) {
        JsonValue edges = node.get("edges");
        if (edges == null || !edges.isArray()) return;
        for (JsonValue e : edges.asArray()) {
            String to = e.getString("to", null);
            JsonValue c = e.get("condition");
            TagCondition condition = (c != null && c.isString()) ? parser.parse(c.asString()) : null;
            Map<String, Object> requirements = new HashMap<>();
            JsonValue req = e.get("requirements");
            if (req != null && req.isObject()) {
                for (Map.Entry<String, JsonValue> f : req.asObject().entrySet()) {
                    JsonValue v = f.getValue();
                    requirements.put(f.getKey(), v.isNumber() ? (Object) v.asInt() : v.asString());
                }
            }
            boolean bidirectional = e.getBoolean("bidirectional", false);
            String description = e.getString("description", null);
            engine.connect(fromId, to, condition, requirements, bidirectional, description);
        }
    }

    private static Set<String> parseStringSet(JsonValue node, String prop) {
        Set<String> set = new HashSet<>();
        JsonValue arr = node.get(prop);
        if (arr == null || !arr.isArray()) return set;
        for (JsonValue item : arr.asArray()) set.add(item.asString());
        return set;
    }

    private static Map<String, Double> parseFloatMap(JsonValue node, String prop) {
        Map<String, Double> map = new HashMap<>();
        JsonValue obj = node.get(prop);
        if (obj == null || !obj.isObject()) return map;
        for (Map.Entry<String, JsonValue> f : obj.asObject().entrySet()) {
            map.put(f.getKey(), f.getValue().asDouble());
        }
        return map;
    }
}

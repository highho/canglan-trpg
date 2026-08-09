package com.canglan.core.tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.core.effect.EffectParser;
import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;

/**
 * 标签注册表：从 JSON 加载全部 TagDef。对应 C# TagRegistry。
 * 数据加载时校验 allowedSources，运行时不动。
 */
public final class TagRegistry {

    /** [适性]标签的涌现效果：所有层级条件要求 -N 级。 */
    private int tierReduction;

    private final Map<String, TagDef> defsById = new LinkedHashMap<>();
    private final Map<TagCategory, List<TagDef>> defsByCategory = new HashMap<>();

    public void loadFromText(String json, EffectParser effectParser) {
        JsonValue root = JsonReader.parse(json);
        if (!root.isObject()) throw new IllegalArgumentException("tags.json 根节点必须是对象");
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue node = entry.getValue();
            TagDef def = new TagDef(
                    id,
                    node.getString("name", id),
                    node.getString("description", ""),
                    parseCategory(node),
                    node.getInt("tier", 1),
                    parseSources(node),
                    effectParser.parseEffects(node),
                    parseBehaviorWeights(node));
            register(def);
        }
        // 注册为当前激活注册表（供 TagTierAtLeast 等条件访问）
        TagRegistryHolder.set(this);
    }

    public void register(TagDef def) {
        defsById.put(def.id(), def);
        defsByCategory.computeIfAbsent(def.category(), k -> new ArrayList<>()).add(def);
    }

    public TagDef get(String id) {
        TagDef def = defsById.get(id);
        if (def == null) throw new IllegalArgumentException("未知标签: " + id);
        return def;
    }

    /** 不存在返回 null（对应 C# TryGet）。 */
    public TagDef tryGet(String id) {
        return defsById.get(id);
    }

    public List<TagDef> getByCategory(TagCategory category) {
        return defsByCategory.getOrDefault(category, List.of());
    }

    public Collection<TagDef> getAll() {
        return defsById.values();
    }

    public int size() { return defsById.size(); }

    public int getTierReduction() { return tierReduction; }
    public void setTierReduction(int tierReduction) { this.tierReduction = tierReduction; }

    // ==================== 解析辅助 ====================

    private static TagCategory parseCategory(JsonValue node) {
        return TagCategory.parse(node.getString("category", "ELEMENT"));
    }

    private static Set<TagSource> parseSources(JsonValue node) {
        Set<TagSource> set = new HashSet<>();
        JsonValue arr = node.get("allowedSources");
        if (arr == null || !arr.isArray()) return set;
        for (JsonValue s : arr.asArray()) set.add(TagSource.parse(s.asString()));
        return set;
    }

    private static Map<String, Map<String, Integer>> parseBehaviorWeights(JsonValue node) {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        JsonValue bw = node.get("behaviorWeights");
        if (bw == null || !bw.isObject()) return result;
        for (Map.Entry<String, JsonValue> pool : bw.asObject().entrySet()) {
            Map<String, Integer> dict = new HashMap<>();
            if (pool.getValue().isArray()) {
                for (JsonValue opt : pool.getValue().asArray()) {
                    dict.put(opt.getString("option", null), opt.getInt("weight", 0));
                }
            }
            result.put(pool.getKey(), dict);
        }
        return result;
    }
}

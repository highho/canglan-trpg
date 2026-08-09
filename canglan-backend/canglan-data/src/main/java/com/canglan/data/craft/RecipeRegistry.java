package com.canglan.data.craft;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.tag.AlwaysTrue;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;

/** 配方注册表（recipes.json，全局配方）。对应 C# RecipeRegistry。显式注入替代静态 Instance。 */
public final class RecipeRegistry {

    private final Map<String, Recipe> recipes = new LinkedHashMap<>();
    private final TagConditionParser parser;

    public RecipeRegistry(TagConditionParser parser) {
        this.parser = parser;
    }

    public void loadFromText(String json) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue n = entry.getValue();

            Map<String, Integer> materials = new LinkedHashMap<>();
            JsonValue ms = n.get("materials");
            if (ms != null && ms.isObject()) {
                for (Map.Entry<String, JsonValue> m : ms.asObject().entrySet()) {
                    if (m.getValue().isNumber()) materials.put(m.getKey(), m.getValue().asInt());
                }
            }

            String condText = n.getString("condition", "");
            TagCondition condition = condText.isEmpty() ? new AlwaysTrue() : parser.parse(condText);
            register(new Recipe(id, n.getString("name", id), materials,
                    n.getString("output", id), n.getInt("outputCount", 1),
                    condition, n.getInt("craftTime", 1)));
        }
    }

    public void register(Recipe r) { recipes.put(r.id(), r); }

    public Recipe get(String id) {
        Recipe r = recipes.get(id);
        if (r == null) throw new IllegalArgumentException("未知配方: " + id);
        return r;
    }

    public Collection<Recipe> getAll() { return recipes.values(); }
    public int size() { return recipes.size(); }
}

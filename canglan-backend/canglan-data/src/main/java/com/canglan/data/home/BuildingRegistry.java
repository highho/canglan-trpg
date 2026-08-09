package com.canglan.data.home;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.tag.AlwaysTrue;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;

/** 建筑蓝图注册表（buildings.json）。对应 C# BuildingRegistry。显式注入替代静态 Instance。 */
public final class BuildingRegistry {

    private final Map<String, BuildingDef> defs = new LinkedHashMap<>();
    private final TagConditionParser parser;

    public BuildingRegistry(TagConditionParser parser) {
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
            String prerequisite = n.has("prerequisite") ? n.getString("prerequisite", null) : null;

            String effectType = null;
            Map<String, Object> effectParams = new LinkedHashMap<>();
            JsonValue ef = n.get("effect");
            if (ef != null && ef.isObject()) {
                effectType = ef.getString("type", "").toUpperCase();
                JsonValue pr = ef.get("params");
                if (pr != null && pr.isObject()) {
                    for (Map.Entry<String, JsonValue> p : pr.asObject().entrySet()) {
                        JsonValue v = p.getValue();
                        if (v.isNumber()) effectParams.put(p.getKey(), v.asDouble());
                        else if (v.isString()) effectParams.put(p.getKey(), v.asString());
                    }
                }
            }

            register(new BuildingDef(id, n.getString("name", id),
                    n.getString("category", "UTILITY").toUpperCase(),
                    materials, condition, prerequisite, effectType, effectParams));
        }
    }

    public void register(BuildingDef def) { defs.put(def.id(), def); }

    public BuildingDef get(String id) {
        BuildingDef def = defs.get(id);
        if (def == null) throw new IllegalArgumentException("未知建筑: " + id);
        return def;
    }

    public BuildingDef tryGet(String id) { return defs.get(id); }

    public Collection<String> getAllIds() { return defs.keySet(); }
    public Collection<BuildingDef> getAll() { return defs.values(); }
    public int size() { return defs.size(); }
}

package com.canglan.data.npc;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;

/** NPC 注册表（从 npcs.json 加载）。对应 C# NpcRegistry。显式注入替代静态 Instance。 */
public final class NpcRegistry {

    private final Map<String, NpcDef> defs = new LinkedHashMap<>();

    public void loadFromText(String json) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue n = entry.getValue();

            Map<String, Float> stats = new HashMap<>();
            JsonValue bs = n.get("baseStats");
            if (bs != null && bs.isObject()) {
                for (Map.Entry<String, JsonValue> p : bs.asObject().entrySet()) {
                    if (p.getValue().isNumber()) stats.put(p.getKey(), (float) p.getValue().asDouble());
                }
            }

            register(new NpcDef(
                    id,
                    n.getString("name", id),
                    readStringSet(n, "identityTags"),
                    readStringSet(n, "personalityTags"),
                    stats,
                    n.getString("relation", "NEUTRAL").toUpperCase(),
                    readStringSet(n, "groups"),
                    n.get("dialogueTree")));
        }
    }

    public void register(NpcDef def) { defs.put(def.id(), def); }

    public NpcDef get(String id) {
        NpcDef def = defs.get(id);
        if (def == null) throw new IllegalArgumentException("未知NPC: " + id);
        return def;
    }

    public NpcDef tryGet(String id) { return defs.get(id); }

    public Collection<NpcDef> getAll() { return defs.values(); }
    public int size() { return defs.size(); }

    private static Set<String> readStringSet(JsonValue node, String prop) {
        Set<String> set = new HashSet<>();
        JsonValue arr = node.get(prop);
        if (arr != null && arr.isArray()) {
            for (JsonValue e : arr.asArray()) if (e.isString()) set.add(e.asString());
        }
        return set;
    }
}

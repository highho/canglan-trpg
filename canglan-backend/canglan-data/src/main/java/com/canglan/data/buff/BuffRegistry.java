package com.canglan.data.buff;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.canglan.core.effect.EffectParser;
import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;

/** Buff 定义注册表（纯数据容器，从JSON加载）。对应 C# BuffRegistry。 */
public final class BuffRegistry {

    private final Map<String, BuffDef> defsById = new LinkedHashMap<>();

    public void loadFromText(String json, EffectParser effectParser) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue node = entry.getValue();
            BuffDef def = new BuffDef(
                    id,
                    node.getString("name", id),
                    BuffType.parse(node.getString("type", "TEMPORARY")),
                    node.getInt("defaultDuration", 3),
                    effectParser.parseEffects(node),
                    node.getBoolean("stackable", false),
                    node.getInt("maxStacks", 1));
            register(def);
        }
    }

    public void register(BuffDef def) { defsById.put(def.id(), def); }

    public BuffDef get(String id) {
        BuffDef def = defsById.get(id);
        if (def == null) throw new IllegalArgumentException("未知Buff: " + id);
        return def;
    }

    public BuffDef tryGet(String id) { return defsById.get(id); }
    public Collection<BuffDef> getAll() { return defsById.values(); }
    public int size() { return defsById.size(); }
}

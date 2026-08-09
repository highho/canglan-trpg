package com.canglan.data.craft;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;

/** 资源注册表（从 resources.json 加载）。对应 C# ResourceRegistry。显式注入替代静态 Instance。 */
public final class ResourceRegistry {

    private final Map<String, GatherableResource> defs = new LinkedHashMap<>();

    public void loadFromText(String json) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue n = entry.getValue();
            register(new GatherableResource(
                    id,
                    n.getString("name", id),
                    ResourceCategory.parse(n.getString("category", "ORE")),
                    n.getInt("difficulty", 0),
                    n.getInt("yieldPerAction", 1),
                    n.getInt("maxYield", 10),
                    n.getString("requiredTag", null)));
        }
    }

    public void register(GatherableResource r) { defs.put(r.id(), r); }

    public GatherableResource get(String id) {
        GatherableResource r = defs.get(id);
        if (r == null) throw new IllegalArgumentException("未知资源: " + id);
        return r;
    }

    public GatherableResource tryGet(String id) { return defs.get(id); }

    public Collection<GatherableResource> getAll() { return defs.values(); }
    public int size() { return defs.size(); }
}

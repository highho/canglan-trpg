package com.canglan.data.trait;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;

/** 特质注册表（traits.json）。对应 C# TraitRegistry。显式注入替代静态 Instance。 */
public final class TraitRegistry {

    private final Map<String, TraitDef> traits = new LinkedHashMap<>();

    public void loadFromText(String json) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue n = entry.getValue();
            Set<String> tagIds = new HashSet<>();
            JsonValue ts = n.get("tagIds");
            if (ts != null && ts.isArray()) {
                for (JsonValue t : ts.asArray()) if (t.isString()) tagIds.add(t.asString());
            }
            String rr = n.has("raceRestriction") ? n.getString("raceRestriction", null) : null;
            String cr = n.has("classRestriction") ? n.getString("classRestriction", null) : null;
            register(new TraitDef(id, n.getString("name", id), tagIds,
                    n.getInt("startingGold", 0), rr, cr, n.getString("description", "")));
        }
    }

    public void register(TraitDef t) { traits.put(t.id(), t); }

    public TraitDef get(String id) {
        TraitDef t = traits.get(id);
        if (t == null) throw new IllegalArgumentException("未知特质: " + id);
        return t;
    }

    public TraitDef tryGet(String id) { return traits.get(id); }

    public Collection<TraitDef> getAll() { return traits.values(); }
    public int size() { return traits.size(); }
}

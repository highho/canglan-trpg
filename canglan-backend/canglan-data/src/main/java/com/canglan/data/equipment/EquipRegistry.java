package com.canglan.data.equipment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.canglan.core.effect.EffectParser;
import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;

/** 装备注册表（从 equipments.json 加载）。对应 C# EquipRegistry。 */
public final class EquipRegistry {

    private final Map<String, EquipDef> defsById = new LinkedHashMap<>();

    public void loadFromText(String json, EffectParser effectParser, TagConditionParser conditionParser) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue node = entry.getValue();
            JsonValue ec = node.get("equipCondition");
            TagCondition condition = (ec != null && ec.isString()) ? conditionParser.parse(ec.asString()) : null;
            Map<String, Double> baseStats = new HashMap<>();
            JsonValue bs = node.get("baseStats");
            if (bs != null && bs.isObject()) {
                for (Map.Entry<String, JsonValue> f : bs.asObject().entrySet()) {
                    baseStats.put(f.getKey(), f.getValue().asDouble());
                }
            }
            List<String> tagIds = null;
            JsonValue tags = node.get("tagIds");
            if (tags != null && tags.isArray()) {
                tagIds = new ArrayList<>();
                for (JsonValue t : tags.asArray()) tagIds.add(t.asString());
            }
            EquipDef def = new EquipDef(
                    id,
                    node.getString("name", id),
                    EquipSlot.parse(node.getString("slot", "WEAPON")),
                    node.getInt("tier", 1),
                    baseStats,
                    effectParser.parseEffects(node),
                    condition,
                    node.getInt("maxDurability", 50),
                    node.getString("setId", null),
                    node.getString("upgradePath", null),
                    tagIds);
            register(def);
        }
    }

    public void register(EquipDef def) { defsById.put(def.id(), def); }

    public EquipDef get(String id) {
        EquipDef def = defsById.get(id);
        if (def == null) throw new IllegalArgumentException("未知装备: " + id);
        return def;
    }

    public EquipDef tryGet(String id) { return defsById.get(id); }
    public Collection<EquipDef> getAll() { return defsById.values(); }
    public int size() { return defsById.size(); }
}

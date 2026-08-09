package com.canglan.data.monster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;

/**
 * 怪物模板注册表（从 monsters.json 加载）。对应 C# MonsterTemplateRegistry。
 * 显式注入替代静态 Instance。
 */
public final class MonsterTemplateRegistry {

    private final Map<String, MonsterTemplate> templates = new LinkedHashMap<>();

    public void loadFromText(String json) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            register(parseTemplate(entry.getKey(), entry.getValue()));
        }
    }

    public void register(MonsterTemplate t) { templates.put(t.id(), t); }

    public MonsterTemplate get(String id) {
        MonsterTemplate t = templates.get(id);
        if (t == null) throw new IllegalArgumentException("未知怪物模板: " + id);
        return t;
    }

    /** 不存在返回 null（对应 C# TryGet）。 */
    public MonsterTemplate tryGet(String id) { return templates.get(id); }

    public Collection<MonsterTemplate> getAll() { return templates.values(); }
    public int size() { return templates.size(); }

    private static MonsterTemplate parseTemplate(String id, JsonValue node) {
        Map<String, Float> stats = new HashMap<>();
        JsonValue bs = node.get("baseStats");
        if (bs != null && bs.isObject()) {
            for (Map.Entry<String, JsonValue> p : bs.asObject().entrySet()) {
                if (p.getValue().isNumber()) stats.put(p.getKey(), (float) p.getValue().asDouble());
            }
        }

        Set<String> raceTags = readStringSet(node, "raceTagIds");
        Set<String> personalityTags = readStringSet(node, "personalityTagIds");

        List<String> pool = new ArrayList<>();
        JsonValue bp = node.get("behaviorPool");
        if (bp != null && bp.isArray()) {
            for (JsonValue e : bp.asArray()) if (e.isString()) pool.add(e.asString());
        } else {
            pool.add("attack"); pool.add("defend"); pool.add("flee");
        }

        List<LootEntry> drops = new ArrayList<>();
        JsonValue dr = node.get("drops");
        if (dr != null && dr.isArray()) {
            for (JsonValue d : dr.asArray()) {
                if (d.isString()) {
                    drops.add(new LootEntry(d.asString(), 0.5f, 1, 1, null));
                    continue;
                }
                drops.add(new LootEntry(
                        d.getString("itemId", null),
                        (float) d.getDouble("chance", 0.5),
                        d.getInt("min", 1),
                        d.getInt("max", 1),
                        d.getString("conditionTag", null)));
            }
        }

        Map<String, Integer> skills = new HashMap<>();
        JsonValue ss = node.get("specialSkills");
        if (ss != null && ss.isObject()) {
            for (Map.Entry<String, JsonValue> p : ss.asObject().entrySet()) {
                if (p.getValue().isNumber()) skills.put(p.getKey(), p.getValue().asInt());
            }
        }

        return new MonsterTemplate(
                id,
                node.getString("name", id),
                stats, raceTags, personalityTags, pool, drops,
                node.getInt("expReward", 0),
                CombatRole.parse(node.getString("combatRole", "MELEE")),
                skills);
    }

    private static Set<String> readStringSet(JsonValue node, String prop) {
        Set<String> set = new HashSet<>();
        JsonValue arr = node.get(prop);
        if (arr != null && arr.isArray()) {
            for (JsonValue e : arr.asArray()) if (e.isString()) set.add(e.asString());
        }
        return set;
    }
}

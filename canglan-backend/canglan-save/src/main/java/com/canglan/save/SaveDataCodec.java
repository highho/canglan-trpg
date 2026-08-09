package com.canglan.save;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.core.json.JsonValue;
import com.canglan.world.FogRow;

/**
 * SaveDataCodec — SaveData 与 JSON 的双向映射（零依赖，替代 C# System.Text.Json）。
 * toMap 输出 JsonWriter 可序列化的嵌套 Map/List；fromJson 从 JsonValue 还原。
 */
public final class SaveDataCodec {

    private SaveDataCodec() {}

    // ==================== 序列化 ====================

    public static Map<String, Object> toMap(SaveData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", d.version);
        m.put("timestamp", d.timestamp);
        m.put("playTime", d.playTime);
        m.put("locationName", d.locationName);

        m.put("playerName", d.playerName);
        m.put("currentRaceId", d.currentRaceId);
        m.put("currentClassId", d.currentClassId);
        m.put("questTagIds", new ArrayList<>(d.questTagIds));
        m.put("traitTagIds", new ArrayList<>(d.traitTagIds));
        m.put("level", d.level);
        m.put("exp", d.exp);
        m.put("gold", d.gold);

        m.put("hunger", d.hunger);
        m.put("thirst", d.thirst);
        m.put("temperature", d.temperature);
        m.put("sanity", d.sanity);

        m.put("gameDay", d.gameDay);
        m.put("gameHour", d.gameHour);
        m.put("difficulty", d.difficulty);

        m.put("worldX", d.worldX);
        m.put("worldY", d.worldY);
        m.put("mapLayer", d.mapLayer);
        m.put("biomeId", d.biomeId);
        List<Object> fog = new ArrayList<>();
        for (FogRow row : d.fogOfWar) {
            Map<String, Object> fr = new LinkedHashMap<>();
            fr.put("y", row.y());
            fr.put("states", row.states());
            fog.add(fr);
        }
        m.put("fogOfWar", fog);

        m.put("inventory", d.inventory);
        m.put("quickBar", d.quickBar);
        m.put("lockedItems", new ArrayList<>(d.lockedItems));
        m.put("equippedItems", d.equippedItems);

        m.put("home", d.home != null ? homeToMap(d.home) : null);

        m.put("npcAffinities", d.npcAffinities);
        m.put("factionReputations", d.factionReputations);
        List<Object> companions = new ArrayList<>();
        for (CompanionSaveData c : d.companions) companions.add(companionToMap(c));
        m.put("companions", companions);
        Map<String, Object> quests = new LinkedHashMap<>();
        for (Map.Entry<String, QuestSaveData> kv : d.quests.entrySet()) quests.put(kv.getKey(), questToMap(kv.getValue()));
        m.put("quests", quests);
        m.put("worldFlags", d.worldFlags);
        m.put("stepCount", d.stepCount);
        return m;
    }

    private static Map<String, Object> homeToMap(HomeSaveData h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", h.level);
        m.put("x", h.x);
        m.put("y", h.y);
        m.put("gridWidth", h.gridWidth);
        m.put("gridHeight", h.gridHeight);
        List<Object> buildings = new ArrayList<>();
        for (BuildingSaveData b : h.buildings) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("buildingId", b.buildingId);
            bm.put("gridX", b.gridX);
            bm.put("gridY", b.gridY);
            bm.put("level", b.level);
            bm.put("state", b.state);
            bm.put("buildProgress", b.buildProgress);
            buildings.add(bm);
        }
        m.put("buildings", buildings);
        return m;
    }

    private static Map<String, Object> companionToMap(CompanionSaveData c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name);
        m.put("currentRaceId", c.currentRaceId);
        m.put("currentClassId", c.currentClassId);
        m.put("questTagIds", new ArrayList<>(c.questTagIds));
        m.put("traitTagIds", new ArrayList<>(c.traitTagIds));
        m.put("level", c.level);
        m.put("exp", c.exp);
        m.put("recruitmentType", c.recruitmentType);
        m.put("contractRemaining", c.contractRemaining);
        m.put("affinity", c.affinity);
        m.put("allyAffinities", c.allyAffinities);
        m.put("inventory", c.inventory);
        return m;
    }

    private static Map<String, Object> questToMap(QuestSaveData q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questId", q.questId);
        m.put("currentStep", q.currentStep);
        m.put("flags", q.flags);
        m.put("cooldownRemaining", q.cooldownRemaining);
        return m;
    }

    // ==================== 反序列化 ====================

    public static SaveData fromJson(JsonValue root) {
        if (root == null || !root.isObject()) return null;
        SaveData d = new SaveData();
        d.version = root.getInt("version", 1);
        d.timestamp = getLong(root, "timestamp");
        d.playTime = getLong(root, "playTime");
        d.locationName = root.getString("locationName", null);

        d.playerName = root.getString("playerName", null);
        d.currentRaceId = root.getString("currentRaceId", null);
        d.currentClassId = root.getString("currentClassId", null);
        d.questTagIds = strSet(root, "questTagIds");
        d.traitTagIds = strSet(root, "traitTagIds");
        d.level = root.getInt("level", 1);
        d.exp = root.getInt("exp", 0);
        d.gold = root.getInt("gold", 0);

        d.hunger = root.getInt("hunger", 100);
        d.thirst = root.getInt("thirst", 100);
        d.temperature = root.getInt("temperature", 100);
        d.sanity = root.getInt("sanity", 100);

        d.gameDay = root.getInt("gameDay", 1);
        d.gameHour = root.getInt("gameHour", 6);
        d.difficulty = root.getString("difficulty", "NORMAL");

        d.worldX = root.getInt("worldX", 0);
        d.worldY = root.getInt("worldY", 0);
        d.mapLayer = root.getString("mapLayer", "Surface");
        d.biomeId = root.getString("biomeId", "Plains");
        d.fogOfWar = fogFromJson(root.get("fogOfWar"));

        d.inventory = intMap(root.get("inventory"));
        d.quickBar = strList(root.get("quickBar"));
        d.lockedItems = strSet(root, "lockedItems");
        d.equippedItems = strStrMap(root.get("equippedItems"));

        d.home = homeFromJson(root.get("home"));

        d.npcAffinities = intMap(root.get("npcAffinities"));
        d.factionReputations = factionMap(root.get("factionReputations"));
        d.companions = companionsFromJson(root.get("companions"));
        d.quests = questsFromJson(root.get("quests"));
        d.worldFlags = boolMap(root.get("worldFlags"));
        d.stepCount = root.getInt("stepCount", 0);
        return d;
    }

    private static List<FogRow> fogFromJson(JsonValue node) {
        List<FogRow> rows = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonValue fr : node.asArray()) {
                if (fr.isObject()) rows.add(new FogRow(fr.getInt("y", 0), fr.getString("states", null)));
            }
        }
        return rows;
    }

    private static HomeSaveData homeFromJson(JsonValue node) {
        if (node == null || !node.isObject()) return null;
        HomeSaveData h = new HomeSaveData();
        h.level = node.getInt("level", 0);
        h.x = node.getInt("x", 0);
        h.y = node.getInt("y", 0);
        h.gridWidth = node.getInt("gridWidth", 0);
        h.gridHeight = node.getInt("gridHeight", 0);
        JsonValue buildings = node.get("buildings");
        if (buildings != null && buildings.isArray()) {
            for (JsonValue b : buildings.asArray()) {
                BuildingSaveData bd = new BuildingSaveData();
                bd.buildingId = b.getString("buildingId", null);
                bd.gridX = b.getInt("gridX", 0);
                bd.gridY = b.getInt("gridY", 0);
                bd.level = b.getInt("level", 0);
                bd.state = b.getString("state", null);
                bd.buildProgress = b.getInt("buildProgress", 0);
                h.buildings.add(bd);
            }
        }
        return h;
    }

    private static List<CompanionSaveData> companionsFromJson(JsonValue node) {
        List<CompanionSaveData> list = new ArrayList<>();
        if (node == null || !node.isArray()) return list;
        for (JsonValue cn : node.asArray()) {
            CompanionSaveData c = new CompanionSaveData();
            c.name = cn.getString("name", null);
            c.currentRaceId = cn.getString("currentRaceId", null);
            c.currentClassId = cn.getString("currentClassId", null);
            c.questTagIds = strSet(cn, "questTagIds");
            c.traitTagIds = strSet(cn, "traitTagIds");
            c.level = cn.getInt("level", 1);
            c.exp = cn.getInt("exp", 0);
            c.recruitmentType = cn.getString("recruitmentType", "Bond");
            c.contractRemaining = cn.getInt("contractRemaining", -1);
            c.affinity = cn.getInt("affinity", 0);
            c.allyAffinities = intMap(cn.get("allyAffinities"));
            c.inventory = intMap(cn.get("inventory"));
            list.add(c);
        }
        return list;
    }

    private static Map<String, QuestSaveData> questsFromJson(JsonValue node) {
        Map<String, QuestSaveData> map = new HashMap<>();
        if (node == null || !node.isObject()) return map;
        for (Map.Entry<String, JsonValue> kv : node.asObject().entrySet()) {
            QuestSaveData q = new QuestSaveData();
            q.questId = kv.getValue().getString("questId", kv.getKey());
            q.currentStep = kv.getValue().getInt("currentStep", 0);
            q.flags = boolMap(kv.getValue().get("flags"));
            q.cooldownRemaining = kv.getValue().getInt("cooldownRemaining", 0);
            map.put(kv.getKey(), q);
        }
        return map;
    }

    // ==================== 辅助 ====================

    private static long getLong(JsonValue node, String key) {
        JsonValue v = node.get(key);
        return v != null && v.isNumber() ? (long) v.asDouble() : 0L;
    }

    private static Set<String> strSet(JsonValue node, String key) {
        Set<String> set = new HashSet<>();
        JsonValue arr = node.get(key);
        if (arr != null && arr.isArray()) {
            for (JsonValue v : arr.asArray()) {
                if (v.isString()) set.add(v.asString());
            }
        }
        return set;
    }

    private static List<String> strList(JsonValue arr) {
        List<String> list = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonValue v : arr.asArray()) {
                if (v.isString()) list.add(v.asString());
            }
        }
        return list;
    }

    private static Map<String, Integer> intMap(JsonValue node) {
        Map<String, Integer> map = new HashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonValue> kv : node.asObject().entrySet()) {
                if (kv.getValue().isNumber()) map.put(kv.getKey(), kv.getValue().asInt());
            }
        }
        return map;
    }

    private static Map<String, String> strStrMap(JsonValue node) {
        Map<String, String> map = new HashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonValue> kv : node.asObject().entrySet()) {
                if (kv.getValue().isString()) map.put(kv.getKey(), kv.getValue().asString());
            }
        }
        return map;
    }

    private static Map<String, Boolean> boolMap(JsonValue node) {
        Map<String, Boolean> map = new HashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonValue> kv : node.asObject().entrySet()) {
                if (kv.getValue().isBoolean()) map.put(kv.getKey(), kv.getValue().asBoolean());
            }
        }
        return map;
    }

    private static Map<String, Map<String, Integer>> factionMap(JsonValue node) {
        Map<String, Map<String, Integer>> map = new HashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonValue> kv : node.asObject().entrySet()) {
                map.put(kv.getKey(), intMap(kv.getValue()));
            }
        }
        return map;
    }
}

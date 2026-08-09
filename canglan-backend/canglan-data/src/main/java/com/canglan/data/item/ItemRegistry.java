package com.canglan.data.item;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;

/**
 * 物品注册表（从 items.json 加载）。对应 C# ItemRegistry。
 * 显式注入替代静态 Instance（Inventory 构造时传入）。
 */
public final class ItemRegistry {

    private final Map<String, ItemDef> defsById = new LinkedHashMap<>();

    public void loadFromText(String json) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue node = entry.getValue();
            ItemDef def = new ItemDef(
                    id,
                    node.getString("name", id),
                    ItemType.parse(node.getString("type", "MISC")),
                    node.getInt("value", 0),
                    node.getString("description", ""),
                    node.getInt("maxStack", 99),
                    node.getInt("nutrition", 0),
                    node.getDouble("weight", 0.5),
                    node.getInt("rarity", 0));
            register(def);
        }
    }

    /** 未注册物品自动登记为 Misc（掉落物/任务物品容错）。 */
    public ItemDef getOrRegister(String id) {
        ItemDef def = defsById.get(id);
        if (def != null) return def;
        def = ItemDef.fallback(id);
        register(def);
        return def;
    }

    public void register(ItemDef def) { defsById.put(def.id(), def); }

    public ItemDef get(String id) {
        ItemDef def = defsById.get(id);
        if (def == null) throw new IllegalArgumentException("未知物品: " + id);
        return def;
    }

    /** 不存在返回 null（对应 C# TryGet）。 */
    public ItemDef tryGet(String id) { return defsById.get(id); }

    public Collection<ItemDef> getAll() { return defsById.values(); }
    public int size() { return defsById.size(); }
}

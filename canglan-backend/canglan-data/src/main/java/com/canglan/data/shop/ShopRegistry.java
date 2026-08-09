package com.canglan.data.shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;

/** 商店注册表（shops.json）。对应 C# ShopRegistry。显式注入替代静态 Instance。 */
public final class ShopRegistry {

    private final Map<String, ShopDef> shopsById = new LinkedHashMap<>();
    private final Map<String, String> ownerToShop = new HashMap<>();   // npcId → shopId

    public void loadFromText(String json) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue n = entry.getValue();
            List<ShopDef.ShopItemDef> items = new ArrayList<>();
            JsonValue arr = n.get("items");
            if (arr != null && arr.isArray()) {
                for (JsonValue it : arr.asArray()) {
                    items.add(new ShopDef.ShopItemDef(
                            it.getString("itemId", ""),
                            it.getInt("price", 10),
                            it.getInt("stock", 10)));
                }
            }
            register(new ShopDef(id, n.getString("name", id), n.getString("owner", ""), items));
        }
    }

    public void register(ShopDef shop) {
        shopsById.put(shop.id(), shop);
        if (shop.owner() != null && !shop.owner().isEmpty()) ownerToShop.put(shop.owner(), shop.id());
    }

    public ShopDef tryGet(String id) { return shopsById.get(id); }

    /** 按店主 NPC id 查商店，无则 null。 */
    public ShopDef getByOwner(String npcId) {
        String id = ownerToShop.get(npcId);
        return id == null ? null : shopsById.get(id);
    }

    public Collection<ShopDef> getAll() { return shopsById.values(); }
    public int size() { return shopsById.size(); }
}

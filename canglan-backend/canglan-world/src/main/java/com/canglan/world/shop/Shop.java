package com.canglan.world.shop;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.canglan.data.item.ItemDef;
import com.canglan.data.item.ItemRegistry;
import com.canglan.data.item.ItemType;
import com.canglan.data.shop.ShopDef;
import com.canglan.world.unit.Unit;

/**
 * Shop — 商店运行时实例：货架库存独立于定义，可消耗与补货。对应 C# ShopSystem.Shop
 * （静态 ItemRegistry.Instance 改为构造注入）。
 */
public final class Shop {

    private final ShopDef def;
    private final ItemRegistry itemRegistry;
    private final Map<String, Integer> stock = new HashMap<>();   // itemId → 当前库存

    public Shop(ShopDef def, ItemRegistry itemRegistry) {
        this.def = def;
        this.itemRegistry = itemRegistry;
        for (ShopDef.ShopItemDef it : def.items()) stock.put(it.itemId(), it.stock());
    }

    public ShopDef def() { return def; }
    public Map<String, Integer> stock() { return stock; }

    public ShopDef.ShopItemDef getItem(String itemId) {
        for (ShopDef.ShopItemDef it : def.items()) if (it.itemId().equals(itemId)) return it;
        return null;
    }

    public int currentStock(String itemId) { return stock.getOrDefault(itemId, 0); }

    /** 买入：金币→物品。返回结果文本。 */
    public String buy(Unit player, String itemId, int count) {
        ShopDef.ShopItemDef it = getItem(itemId);
        if (it == null) return "【" + itemId + "】不在货架上。";
        if (count < 1) count = 1;
        if (currentStock(itemId) < count)
            return "【" + itemRegistry.get(itemId).name() + "】库存不足（余 " + currentStock(itemId) + "）。";
        int total = it.price() * count;
        if (player.gold() < total)
            return "金币不足，需要 " + total + " 枚（你只有 " + player.gold() + "）。";
        player.setGold(player.gold() - total);
        player.inventory().add(itemId, count);
        stock.put(itemId, stock.get(itemId) - count);
        return "你购得 " + itemRegistry.get(itemId).name() + " x" + count + "，花费 " + total + " 金币。";
    }

    /** 卖出：物品→金币（半价回收，锁定物品不可卖）。返回结果文本。 */
    public String sell(Unit player, String itemId, int count, Set<String> lockedItems) {
        ItemDef itemDef = itemRegistry.tryGet(itemId);
        if (itemDef == null || itemDef.value() <= 0) return "这东西不值钱，商人不收。";
        if (itemDef.type() == ItemType.EQUIPMENT) return "装备请去铁匠铺处理（锻造面板）。";
        if (lockedItems != null && lockedItems.contains(itemId))
            return "【" + itemDef.name() + "】已被锁定，无法出售。先解锁再卖。";
        if (count < 1) count = 1;
        if (player.inventory().count(itemId) < count) return "你没有那么多【" + itemDef.name() + "】。";
        int total = itemDef.value() * count / 2;
        player.inventory().remove(itemId, count);
        player.setGold(player.gold() + total);
        return "你出售 " + itemDef.name() + " x" + count + "，获得 " + total + " 金币。";
    }

    /** 每日补货：库存恢复到定义值。 */
    public void restock() {
        for (ShopDef.ShopItemDef it : def.items()) stock.put(it.itemId(), it.stock());
    }
}

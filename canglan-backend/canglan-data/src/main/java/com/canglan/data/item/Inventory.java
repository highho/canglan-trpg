package com.canglan.data.item;

import java.util.HashMap;
import java.util.Map;

/**
 * Inventory — 背包容器。所有 Unit 都有背包：活着交易/打劫/装备，死了尸体=可搜刮容器。
 * 对应 C# Inventory。显式注入 ItemRegistry（Bootstrap 铁律：ItemRegistry 先于 Unit）。
 */
public final class Inventory {

    private final Map<String, ItemStack> stacks = new HashMap<>();
    private final ItemRegistry registry;

    public Inventory(ItemRegistry registry) {
        this.registry = registry;
    }

    public Iterable<ItemStack> stacks() { return stacks.values(); }
    public int stackCount() { return stacks.size(); }

    public void add(String itemId, int count) {
        if (count <= 0) return;
        ItemStack stack = stacks.get(itemId);
        if (stack == null) {
            ItemDef def = registry != null ? registry.getOrRegister(itemId) : ItemDef.fallback(itemId);
            stacks.put(itemId, stack = new ItemStack(def, 0));
        }
        stack.addCount(count);
    }

    public void add(String itemId) { add(itemId, 1); }

    /** 移除物品；数量不足返回 false 且不变更。 */
    public boolean remove(String itemId, int count) {
        ItemStack stack = stacks.get(itemId);
        if (stack == null || stack.count() < count) return false;
        stack.addCount(-count);
        if (stack.count() <= 0) stacks.remove(itemId);
        return true;
    }

    public boolean remove(String itemId) { return remove(itemId, 1); }

    public int count(String itemId) {
        ItemStack stack = stacks.get(itemId);
        return stack == null ? 0 : stack.count();
    }

    /** 当前总负重（Σ 数量×单位重量，开拓者式负重）。 */
    public double totalWeight() {
        double total = 0;
        for (ItemStack s : stacks.values()) total += s.def().weight() * s.count();
        return total;
    }

    public boolean hasItem(String itemId) { return count(itemId) > 0; }

    /** 检查是否持有全部材料。 */
    public boolean hasItems(Map<String, Integer> materials) {
        for (Map.Entry<String, Integer> kv : materials.entrySet()) {
            if (count(kv.getKey()) < kv.getValue()) return false;
        }
        return true;
    }

    /** 批量移除材料（调用前应先 hasItems 检查）。 */
    public void removeAll(Map<String, Integer> materials) {
        for (Map.Entry<String, Integer> kv : materials.entrySet()) remove(kv.getKey(), kv.getValue());
    }

    public void clear() { stacks.clear(); }

    /** 存档序列化：{ itemId: count }。 */
    public Map<String, Integer> toSaveMap() {
        Map<String, Integer> map = new HashMap<>();
        for (Map.Entry<String, ItemStack> kv : stacks.entrySet()) map.put(kv.getKey(), kv.getValue().count());
        return map;
    }

    /** 读档恢复。 */
    public void loadFrom(Map<String, Integer> map) {
        clear();
        if (map == null) return;
        for (Map.Entry<String, Integer> kv : map.entrySet()) add(kv.getKey(), kv.getValue());
    }
}

package com.canglan.data.item;

/** 物品堆。对应 C# ItemStack。 */
public final class ItemStack {
    private final ItemDef def;
    private int count;

    public ItemStack(ItemDef def, int count) {
        this.def = def;
        this.count = count;
    }

    public ItemDef def() { return def; }
    public int count() { return count; }
    public void setCount(int count) { this.count = count; }
    public void addCount(int delta) { this.count += delta; }

    @Override
    public String toString() { return def.name() + "x" + count; }
}

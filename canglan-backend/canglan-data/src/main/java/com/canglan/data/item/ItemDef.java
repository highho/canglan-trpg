package com.canglan.data.item;

/**
 * 物品定义（items.json）。对应 C# ItemDef record。
 *
 * @param value       基础价值（金币）
 * @param maxStack    最大堆叠
 * @param nutrition   营养值（食物恢复饱食度，生存系统用）
 * @param weight      单位重量（负重系统，开拓者式）
 * @param rarity      稀有度 0白/1绿/2蓝/3紫/4金（装备品质，普通物品固定0）
 */
public record ItemDef(
        String id,
        String name,
        ItemType type,
        int value,
        String description,
        int maxStack,
        int nutrition,
        double weight,
        int rarity) {

    /** 未注册物品容错默认定义（Misc）。 */
    public static ItemDef fallback(String id) {
        return new ItemDef(id, id, ItemType.MISC, 0, "", 99, 0, 0.5, 0);
    }
}

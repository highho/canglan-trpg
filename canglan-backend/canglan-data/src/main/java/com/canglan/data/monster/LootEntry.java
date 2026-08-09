package com.canglan.data.monster;

/**
 * 掉落条目。对应 C# LootEntry。
 * conditionTag 为额外条件标签（如 [幸运]），null=无条件。
 */
public record LootEntry(
        String itemId,          // "哥布林之牙"
        float baseChance,       // 0.0 ~ 1.0 基础掉落概率
        int minQuantity,        // 最小数量
        int maxQuantity,        // 最大数量
        String conditionTag) {
}

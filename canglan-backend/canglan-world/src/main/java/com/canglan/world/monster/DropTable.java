package com.canglan.world.monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.canglan.data.monster.LootEntry;
import com.canglan.world.unit.Unit;

/**
 * DropTable — 掉落系统。[幸运]标签 ×1.5 概率加成；条件标签不满足则不掉。
 * 对应 C# DropTable（依赖 Unit，归入世界层）。
 */
public final class DropTable {

    private DropTable() {}

    /** 掷骰掉落物品堆。 */
    public record Drop(String itemId, int count) {}

    public static List<LootEntry> roll(List<LootEntry> entries, Unit killer, Random rng) {
        float luckBonus = killer != null && killer.hasTag("幸运") ? 1.5f : 1.0f;
        List<LootEntry> result = new ArrayList<>();
        for (LootEntry entry : entries) {
            if (entry.conditionTag() != null && (killer == null || !killer.hasTag(entry.conditionTag())))
                continue;
            if (rng.nextDouble() < entry.baseChance() * luckBonus)
                result.add(entry);
        }
        return result;
    }

    /** 掷骰结果 → 物品堆（数量在 min~max 间随机）。 */
    public static List<Drop> generateItems(List<LootEntry> rolled, Random rng) {
        List<Drop> items = new ArrayList<>();
        for (LootEntry e : rolled) {
            int qty = e.minQuantity() + rng.nextInt(e.maxQuantity() - e.minQuantity() + 1);
            items.add(new Drop(e.itemId(), qty));
        }
        return items;
    }
}

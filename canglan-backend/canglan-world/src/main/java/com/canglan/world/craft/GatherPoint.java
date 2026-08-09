package com.canglan.world.craft;

import com.canglan.data.craft.GatherableResource;
import com.canglan.data.craft.ResourceCategory;
import com.canglan.world.unit.Unit;

/**
 * GatherPoint — 采集点实例。储量有限 + 3回合冷却；
 * 效率 = 1 + [采集LvN]层级 + 职业标签加成（矿工/草药师）。对应 C# GatherPoint。
 */
public final class GatherPoint {

    /** 采集产出（物品ID, 数量）。 */
    public record GatherYield(String itemId, int count) {}

    private final GatherableResource resource;
    private int remainingYield;
    private int cooldownRemaining;

    public GatherPoint(GatherableResource resource) {
        this.resource = resource;
        this.remainingYield = resource.maxYield();
    }

    public GatherableResource resource() { return resource; }
    public int remainingYield() { return remainingYield; }
    public int cooldownRemaining() { return cooldownRemaining; }
    public boolean isDepleted() { return remainingYield <= 0; }

    /** 尝试采集，返回 (物品ID, 数量)；失败返回 null。 */
    public GatherYield gather(Unit gatherer) {
        if (cooldownRemaining > 0 || remainingYield <= 0) return null;

        // 标签检查
        if (resource.requiredTag() != null && !gatherer.hasTag(resource.requiredTag()))
            return null;

        // 效率修正
        int efficiency = getGatherEfficiency(gatherer);
        int actual = Math.min(resource.yieldPerAction() * efficiency, remainingYield);

        remainingYield -= actual;
        cooldownRemaining = 3;   // 3回合冷却

        return new GatherYield(resource.id(), actual);
    }

    /** 采集效率：基础1 + [采集LvN]层级 + 类别职业加成。 */
    private int getGatherEfficiency(Unit gatherer) {
        int eff = 1;
        for (String tagId : gatherer.activeTagIds()) {
            if (tagId.startsWith("采集Lv")) {
                try { eff += Integer.parseInt(tagId.substring("采集Lv".length())); }
                catch (NumberFormatException ignored) { }
            }
        }
        if (resource.category() == ResourceCategory.ORE && gatherer.hasTag("矿工")) eff++;
        if (resource.category() == ResourceCategory.HERB && gatherer.hasTag("草药师")) eff++;
        return eff;
    }

    public void tickCooldown() {
        if (cooldownRemaining > 0) cooldownRemaining--;
    }
}

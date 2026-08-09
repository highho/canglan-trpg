package com.canglan.world.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.data.craft.Recipe;
import com.canglan.data.craft.RecipeRegistry;
import com.canglan.data.item.Inventory;
import com.canglan.world.unit.Unit;

/**
 * CraftingSystem — 单位持有的制造系统。标签解锁配方 → 检查材料 → 执行制造。
 * 订阅 TAG_CHANGED（owner=this）：标签变化时自动解锁新配方。对应 C# CraftingSystem
 * （静态 RecipeRegistry.Instance 改为构造注入）。
 */
public final class CraftingSystem {

    private final List<Recipe> knownRecipes = new ArrayList<>();
    private final Unit owner;
    private final EventBus eventBus;
    private final RecipeRegistry recipeRegistry;

    public CraftingSystem(Unit owner, EventBus bus, RecipeRegistry recipeRegistry) {
        this.owner = owner;
        this.eventBus = bus;
        this.recipeRegistry = recipeRegistry;
        bus.subscribeWithOwner(EventTypes.TAG_CHANGED, e -> {
            if (e.source() == owner) unlockRecipes(owner.activeTagIds());
        }, this);
        unlockRecipes(owner.activeTagIds());
    }

    /** 根据标签解锁配方，返回新解锁列表。 */
    public List<Recipe> unlockRecipes(Set<String> tagIds) {
        List<Recipe> newlyUnlocked = new ArrayList<>();
        for (Recipe recipe : recipeRegistry.getAll()) {
            if (knownRecipes.contains(recipe)) continue;
            if (recipe.unlockCondition().evaluate(tagIds)) {
                knownRecipes.add(recipe);
                newlyUnlocked.add(recipe);
            }
        }
        return newlyUnlocked;
    }

    public List<Recipe> getKnownRecipes() { return new ArrayList<>(knownRecipes); }

    /** 检查是否能制造（已解锁 + 材料充足）。 */
    public boolean canCraft(Recipe recipe, Inventory inventory) {
        if (!knownRecipes.contains(recipe)) return false;
        return inventory.hasItems(recipe.materials());
    }

    /** 执行制造：扣材料 → 产出物品 → 发射 ITEM_ACQUIRED。 */
    public CraftResult craft(Recipe recipe, Inventory inventory) {
        if (!knownRecipes.contains(recipe))
            return new CraftResult(false, null, "配方未解锁");
        if (!inventory.hasItems(recipe.materials()))
            return new CraftResult(false, null, "材料不足");

        inventory.removeAll(recipe.materials());
        inventory.add(recipe.outputItemId(), recipe.outputCount());
        eventBus.emit(EventTypes.ITEM_ACQUIRED, owner, recipe.outputItemId());
        return new CraftResult(true, recipe.outputItemId(), null);
    }
}

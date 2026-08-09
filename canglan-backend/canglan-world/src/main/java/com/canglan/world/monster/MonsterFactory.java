package com.canglan.world.monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.core.tag.TagFactory;
import com.canglan.data.item.ItemRegistry;
import com.canglan.data.monster.LootEntry;
import com.canglan.data.monster.MonsterTemplate;
import com.canglan.data.monster.MonsterTemplateRegistry;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.unit.BehaviorOption;
import com.canglan.world.unit.BehaviorPool;
import com.canglan.world.unit.BehaviorPools;
import com.canglan.world.unit.RelationState;
import com.canglan.world.unit.Unit;
import com.canglan.world.unit.UnitRole;

/**
 * MonsterFactory — 从模板创建怪物 Unit。
 * 怪物 = Unit 的战斗偏向：只有 combatPool、固定敌对、无社交能力。
 * 种族/人格标签进 traitTagIds（怪物无进化图身份）；掉落表/经验值存 metadata。
 * 对应 C# MonsterFactory（静态注册表改为构造注入）。
 */
public final class MonsterFactory {

    private final TagFactory tagFactory;
    private final EffectEngine effectEngine;
    private final EventBus eventBus;
    private final ItemRegistry itemRegistry;
    private final MonsterTemplateRegistry registry;
    private final Random rng;

    public MonsterFactory(TagFactory tagFactory, EffectEngine effectEngine, EventBus bus,
                          ItemRegistry itemRegistry, MonsterTemplateRegistry registry, Random rng) {
        this.tagFactory = tagFactory;
        this.effectEngine = effectEngine;
        this.eventBus = bus;
        this.itemRegistry = itemRegistry;
        this.registry = registry;
        this.rng = rng != null ? rng : new Random();
    }

    public Unit create(MonsterTemplate template) {
        Unit monster = new Unit(template.name(), UnitRole.MONSTER, tagFactory, effectEngine, eventBus, itemRegistry);

        // 基础属性
        for (var entry : template.baseStats().entrySet())
            monster.stats().setBase(entry.getKey(), entry.getValue());
        monster.stats().setHp(monster.maxHp());

        // 标签：种族(IDENTITY) + 人格(PERSONALITY) → 特质集合
        monster.traitTagIds().addAll(template.raceTagIds());
        monster.traitTagIds().addAll(template.personalityTagIds());
        monster.recalculateTags();

        // 行为池（只有战斗池）
        monster.setCombatPool(createBehaviorPool(template.id(), template.behaviorPool()));
        monster.setActivePool(monster.combatPool());

        // 关系状态固定为敌对
        monster.setRelationToPlayer(RelationState.HOSTILE);

        // 掉落表/经验值引用
        monster.metadata().put("templateId", template.id());
        monster.metadata().put("drops", template.drops());
        monster.metadata().put("expReward", template.expReward());
        monster.metadata().put("combatRole", template.combatRole());
        monster.metadata().put("specialSkills", template.specialSkills());

        return monster;
    }

    public Unit create(String templateId) {
        return create(registry.get(templateId));
    }

    /** 从全局预置选项构建战斗池（未知选项回退默认战斗池选项）。 */
    private BehaviorPool createBehaviorPool(String monsterId, List<String> optionIds) {
        BehaviorPool defaults = BehaviorPools.defaultCombatPool();
        BehaviorPool pool = new BehaviorPool("monster_" + monsterId, monsterId + "战斗池");
        for (String id : optionIds) {
            BehaviorOption option = defaults.find(id);
            if (option != null) pool.add(option);
        }
        if (pool.options().isEmpty()) pool.add(defaults.find("attack"));
        return pool;
    }

    /** 击杀掉落：掷骰 → 物品入击杀者背包 → 发射 ITEM_ACQUIRED。 */
    public List<DropTable.Drop> dropLoot(Unit monster, Unit killer) {
        List<DropTable.Drop> result = new ArrayList<>();
        if (monster.metadata().get("drops") instanceof List<?> raw && !raw.isEmpty()
                && raw.get(0) instanceof LootEntry) {
            @SuppressWarnings("unchecked")
            List<LootEntry> drops = (List<LootEntry>) raw;
            List<LootEntry> rolled = DropTable.roll(drops, killer, rng);
            result = DropTable.generateItems(rolled, rng);
            for (DropTable.Drop drop : result) {
                if (killer != null) killer.inventory().add(drop.itemId(), drop.count());
                eventBus.emit(EventTypes.ITEM_ACQUIRED, killer, drop.itemId());
            }
        }
        return result;
    }
}

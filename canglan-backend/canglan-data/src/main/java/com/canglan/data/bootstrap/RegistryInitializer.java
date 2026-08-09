package com.canglan.data.bootstrap;

import java.nio.file.Path;

import com.canglan.core.effect.EffectParser;
import com.canglan.core.graph.GraphEngine;
import com.canglan.core.graph.GraphLoader;
import com.canglan.core.tag.TagConditionParser;
import com.canglan.core.tag.TagRegistry;
import com.canglan.data.buff.BuffRegistry;
import com.canglan.data.craft.RecipeRegistry;
import com.canglan.data.craft.ResourceRegistry;
import com.canglan.data.equipment.EquipRegistry;
import com.canglan.data.equipment.SetBonusRegistry;
import com.canglan.data.home.BuildingRegistry;
import com.canglan.data.item.ItemRegistry;
import com.canglan.data.load.ConfigLoader;
import com.canglan.data.monster.MonsterTemplateRegistry;
import com.canglan.data.npc.NpcRegistry;
import com.canglan.data.shop.ShopRegistry;
import com.canglan.data.skill.SkillRegistry;
import com.canglan.data.skill.SkillTreeRegistry;
import com.canglan.data.trait.TraitRegistry;

/**
 * RegistryInitializer — 数据层 Bootstrap。
 * 对应 C# GameWorld.Bootstrap 第 1~5 步（标签→物品→Buff/装备/套装→技能→三图）。
 *
 * 加载顺序铁律（不可调换）：
 *  1. tags.json（标签契约层，一切条件/效果的基础）
 *  2. items.json（必须先于任何 Unit 创建，Inventory 依赖）
 *  3. buffs / equipments / setBonuses
 *  4. skills / skillTrees（技能树依赖技能注册表）
 *  5. 三图：races → classes → quests
 *  6. 世界内容注册表：monsters / resources / npcs（P4）
 *  7. P6 运行时数据：recipes / buildings / traits / shops
 */
public final class RegistryInitializer {

    private RegistryInitializer() {}

    /** 按铁律顺序加载 dataDir 下全部核心 JSON，返回装配好的注册表容器。 */
    public static Registries initialize(Path dataDir) {
        TagConditionParser conditionParser = new TagConditionParser();
        EffectParser effectParser = new EffectParser(conditionParser);

        // 1. 标签契约层（一切条件/效果的基础）
        TagRegistry tags = new TagRegistry();
        tags.loadFromText(ConfigLoader.readText(dataDir.resolve("tags.json")), effectParser);

        // 2. 物品（必须先于 Unit 创建）
        ItemRegistry items = new ItemRegistry();
        items.loadFromText(ConfigLoader.readText(dataDir.resolve("items.json")));

        // 3. Buff / 装备 / 套装
        BuffRegistry buffs = new BuffRegistry();
        buffs.loadFromText(ConfigLoader.readText(dataDir.resolve("buffs.json")), effectParser);
        EquipRegistry equips = new EquipRegistry();
        equips.loadFromText(ConfigLoader.readText(dataDir.resolve("equipments.json")), effectParser, conditionParser);
        SetBonusRegistry setBonuses = new SetBonusRegistry();
        setBonuses.loadFromText(ConfigLoader.readText(dataDir.resolve("setBonuses.json")), effectParser);

        // 4. 技能 + 技能树
        SkillRegistry skills = new SkillRegistry();
        skills.loadFromText(ConfigLoader.readText(dataDir.resolve("skills.json")), effectParser, conditionParser);
        SkillTreeRegistry skillTrees = new SkillTreeRegistry(conditionParser, skills);
        skillTrees.loadFromText(ConfigLoader.readText(dataDir.resolve("skillTrees.json")));

        // 5. 三图（种族进化 / 职业转职 / 任务）
        GraphLoader loader = new GraphLoader(conditionParser, tags);
        GraphEngine<com.canglan.core.graph.RaceData> raceGraph =
                loader.loadRaceGraphFromText(ConfigLoader.readText(dataDir.resolve("races.json")));
        GraphEngine<com.canglan.core.graph.ClassData> classGraph =
                loader.loadClassGraphFromText(ConfigLoader.readText(dataDir.resolve("classes.json")));
        GraphEngine<com.canglan.core.graph.QuestData> questGraph =
                loader.loadQuestGraphFromText(ConfigLoader.readText(dataDir.resolve("quests.json")));

        // 6. 世界内容注册表（怪物模板 / 采集资源 / NPC）
        MonsterTemplateRegistry monsters = new MonsterTemplateRegistry();
        monsters.loadFromText(ConfigLoader.readText(dataDir.resolve("monsters.json")));
        ResourceRegistry resources = new ResourceRegistry();
        resources.loadFromText(ConfigLoader.readText(dataDir.resolve("resources.json")));
        NpcRegistry npcs = new NpcRegistry();
        npcs.loadFromText(ConfigLoader.readText(dataDir.resolve("npcs.json")));

        // 7. P6 运行时数据（配方 / 建筑 / 特质 / 商店）
        RecipeRegistry recipes = new RecipeRegistry(conditionParser);
        recipes.loadFromText(ConfigLoader.readText(dataDir.resolve("recipes.json")));
        BuildingRegistry buildings = new BuildingRegistry(conditionParser);
        buildings.loadFromText(ConfigLoader.readText(dataDir.resolve("buildings.json")));
        TraitRegistry traits = new TraitRegistry();
        traits.loadFromText(ConfigLoader.readText(dataDir.resolve("traits.json")));
        ShopRegistry shops = new ShopRegistry();
        shops.loadFromText(ConfigLoader.readText(dataDir.resolve("shops.json")));

        return new Registries(tags, items, buffs, equips, setBonuses, skills, skillTrees,
                raceGraph, classGraph, questGraph, monsters, resources, recipes,
                buildings, npcs, traits, shops);
    }
}

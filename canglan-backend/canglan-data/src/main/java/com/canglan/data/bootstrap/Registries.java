package com.canglan.data.bootstrap;

import com.canglan.core.graph.ClassData;
import com.canglan.core.graph.GraphEngine;
import com.canglan.core.graph.QuestData;
import com.canglan.core.graph.RaceData;
import com.canglan.data.buff.BuffRegistry;
import com.canglan.data.craft.RecipeRegistry;
import com.canglan.data.craft.ResourceRegistry;
import com.canglan.data.equipment.EquipRegistry;
import com.canglan.data.equipment.SetBonusRegistry;
import com.canglan.data.home.BuildingRegistry;
import com.canglan.data.item.ItemRegistry;
import com.canglan.data.monster.MonsterTemplateRegistry;
import com.canglan.data.npc.NpcRegistry;
import com.canglan.data.shop.ShopRegistry;
import com.canglan.data.skill.SkillRegistry;
import com.canglan.data.skill.SkillTreeRegistry;
import com.canglan.data.trait.TraitRegistry;
import com.canglan.core.tag.TagRegistry;

/**
 * 全部注册表装配结果（对应 C# GameWorld 上挂的各注册表字段）。
 * 世界/存档/AI 模块依赖此容器而非静态单例。
 */
public final class Registries {

    public final TagRegistry tags;
    public final ItemRegistry items;
    public final BuffRegistry buffs;
    public final EquipRegistry equips;
    public final SetBonusRegistry setBonuses;
    public final SkillRegistry skills;
    public final SkillTreeRegistry skillTrees;
    public final GraphEngine<RaceData> raceGraph;
    public final GraphEngine<ClassData> classGraph;
    public final GraphEngine<QuestData> questGraph;
    public final MonsterTemplateRegistry monsters;
    public final ResourceRegistry resources;
    public final RecipeRegistry recipes;
    public final BuildingRegistry buildings;
    public final NpcRegistry npcs;
    public final TraitRegistry traits;
    public final ShopRegistry shops;

    Registries(TagRegistry tags, ItemRegistry items, BuffRegistry buffs, EquipRegistry equips,
               SetBonusRegistry setBonuses, SkillRegistry skills, SkillTreeRegistry skillTrees,
               GraphEngine<RaceData> raceGraph, GraphEngine<ClassData> classGraph,
               GraphEngine<QuestData> questGraph,
               MonsterTemplateRegistry monsters, ResourceRegistry resources, RecipeRegistry recipes,
               BuildingRegistry buildings, NpcRegistry npcs, TraitRegistry traits, ShopRegistry shops) {
        this.tags = tags;
        this.items = items;
        this.buffs = buffs;
        this.equips = equips;
        this.setBonuses = setBonuses;
        this.skills = skills;
        this.skillTrees = skillTrees;
        this.raceGraph = raceGraph;
        this.classGraph = classGraph;
        this.questGraph = questGraph;
        this.monsters = monsters;
        this.resources = resources;
        this.recipes = recipes;
        this.buildings = buildings;
        this.npcs = npcs;
        this.traits = traits;
        this.shops = shops;
    }
}

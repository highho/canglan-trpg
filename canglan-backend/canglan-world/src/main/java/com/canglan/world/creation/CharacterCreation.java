package com.canglan.world.creation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.canglan.core.graph.ClassNode;
import com.canglan.core.graph.GraphEngine;
import com.canglan.core.graph.GraphNode;
import com.canglan.core.graph.ClassData;
import com.canglan.core.graph.RaceData;
import com.canglan.core.graph.RaceNode;
import com.canglan.core.tag.TagFactory;
import com.canglan.core.eventbus.EventBus;
import com.canglan.data.item.ItemRegistry;
import com.canglan.data.trait.TraitDef;
import com.canglan.data.trait.TraitRegistry;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.unit.Unit;
import com.canglan.world.unit.UnitRole;

/**
 * CharacterCreation — 标签驱动的角色创建：三选（种族+职业+特质）→ recalculateTags → 一切自动确定。
 * 不需要属性点分配、不需要技能选择、不需要微调。对应 C# CharacterCreation。
 */
public final class CharacterCreation {

    private final GraphEngine<RaceData> raceGraph;
    private final GraphEngine<ClassData> classGraph;
    private final TraitRegistry traitRegistry;
    private final TagFactory tagFactory;
    private final EffectEngine effectEngine;
    private final EventBus eventBus;
    private final ItemRegistry itemRegistry;
    private final Set<String> startingRaceIds;   // null = 仅根种族可选（进化形态需经标签进化）

    public CharacterCreation(GraphEngine<RaceData> raceGraph, GraphEngine<ClassData> classGraph,
                             TraitRegistry traitRegistry, TagFactory tagFactory,
                             EffectEngine effectEngine, EventBus eventBus,
                             ItemRegistry itemRegistry, Iterable<String> startingRaceIds) {
        this.raceGraph = raceGraph;
        this.classGraph = classGraph;
        this.traitRegistry = traitRegistry;
        this.tagFactory = tagFactory;
        this.effectEngine = effectEngine;
        this.eventBus = eventBus;
        this.itemRegistry = itemRegistry;
        this.startingRaceIds = startingRaceIds == null ? null : toSet(startingRaceIds);
    }

    private static Set<String> toSet(Iterable<String> ids) {
        Set<String> set = new HashSet<>();
        for (String id : ids) set.add(id);
        return set;
    }

    /** 获取可选种族列表（缺省仅无入边的根种族）。 */
    public List<RaceNode> getAvailableRaces() {
        List<RaceNode> result = new ArrayList<>();
        for (GraphNode<RaceData> n : raceGraph.allNodes()) {
            if (!(n instanceof RaceNode race)) continue;
            boolean selectable = startingRaceIds != null
                    ? startingRaceIds.contains(race.id())
                    : race.incomingEdges().isEmpty();
            if (selectable) result.add(race);
        }
        return result;
    }

    /** 获取可选职业列表（根职业；双向边的回转入边不算来源，进阶职业需转职）。 */
    public List<ClassNode> getAvailableClasses() {
        List<ClassNode> result = new ArrayList<>();
        for (GraphNode<ClassData> n : classGraph.allNodes()) {
            if (!(n instanceof ClassNode cls)) continue;
            if (cls.incomingEdges().stream().allMatch(e -> e.bidirectional())) result.add(cls);
        }
        return result;
    }

    /** 获取某种族+职业下的可选特质（种族/职业限制过滤）。 */
    public List<TraitDef> getAvailableTraits(RaceData race, ClassData cls) {
        List<TraitDef> result = new ArrayList<>();
        for (TraitDef t : traitRegistry.getAll()) {
            if (t.raceRestriction() != null && !t.raceRestriction().equals(race.name())) continue;
            if (t.classRestriction() != null && !t.classRestriction().equals(cls.name())) continue;
            result.add(t);
        }
        return result;
    }

    /** 执行创建：种族+职业+特质 → 全量重建 → 初始装备与金币。 */
    public Unit create(String raceId, String classId, String traitId, String playerName) {
        GraphNode<RaceData> raceRaw = raceGraph.getNode(raceId);
        GraphNode<ClassData> classRaw = classGraph.getNode(classId);
        TraitDef trait = traitRegistry.get(traitId);
        if (!(raceRaw instanceof RaceNode raceNode) || !(classRaw instanceof ClassNode classNode))
            throw new IllegalArgumentException("无效的种族或职业选择");

        Unit player = new Unit(playerName, UnitRole.PLAYER, tagFactory, effectEngine, eventBus, itemRegistry);

        // 1. 种族基础属性
        for (var entry : raceNode.baseStats().entrySet())
            player.stats().setBase(entry.getKey(), entry.getValue().floatValue());

        // 2. 种族 + 职业
        player.changeRace(raceNode);
        player.changeClass(classNode);

        // 3. 特质标签
        for (String tagId : trait.tagIds()) player.traitTagIds().add(tagId);

        // 4. 全量重建 → 标签集/属性/行为偏好全部确定
        player.recalculateTags();
        player.stats().setHp(player.maxHp());

        // 5. 初始装备
        player.inventory().add("healing_potion", 3);
        player.inventory().add("travel_rations", 5);

        // 6. 初始金币 = 特质加成 + 基础100
        player.setGold(trait.startingGold() + 100);

        return player;
    }
}

package com.canglan.save;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.core.graph.ClassData;
import com.canglan.core.graph.ClassNode;
import com.canglan.core.graph.GraphEngine;
import com.canglan.core.graph.RaceData;
import com.canglan.core.graph.RaceNode;
import com.canglan.core.tag.TagFactory;
import com.canglan.data.item.ItemRegistry;
import com.canglan.world.DifficultyMode;
import com.canglan.world.GameTime;
import com.canglan.world.MapLayer;
import com.canglan.world.MapPos;
import com.canglan.world.WorldMap;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.unit.Unit;
import com.canglan.world.unit.UnitRole;

/**
 * GameLoader — 加载流程：读档 → 迁移 → 重建玩家（recalculateTags 全量重建）
 * → 生存/背包/世界（层级+迷雾）/队友 → GAME_LOADED 事件。对应 C# GameLoader。
 * 家园/装备重建留待对应系统迁移后补充。
 */
public final class GameLoader {

    private final SaveManager saveManager;
    private final GraphEngine<RaceData> raceGraph;
    private final GraphEngine<ClassData> classGraph;
    private final TagFactory tagFactory;
    private final EffectEngine effectEngine;
    private final EventBus eventBus;
    private final ItemRegistry itemRegistry;
    private final int worldWidth;
    private final int worldHeight;
    private final GameTime time;   // 可为 null（不恢复时钟）

    public GameLoader(SaveManager saveManager, GraphEngine<RaceData> raceGraph,
                      GraphEngine<ClassData> classGraph, TagFactory tagFactory,
                      EffectEngine effectEngine, EventBus eventBus, ItemRegistry itemRegistry,
                      int worldWidth, int worldHeight, GameTime time) {
        this.saveManager = saveManager;
        this.raceGraph = raceGraph;
        this.classGraph = classGraph;
        this.tagFactory = tagFactory;
        this.effectEngine = effectEngine;
        this.eventBus = eventBus;
        this.itemRegistry = itemRegistry;
        this.worldWidth = worldWidth;
        this.worldHeight = worldHeight;
        this.time = time;
    }

    public GameState load(int slot) {
        SaveData data = saveManager.load(slot);
        if (data == null) throw new IllegalStateException("存档不存在: slot " + slot);
        data = saveManager.migrate(data);

        // 1. 重建玩家（源头数据 → recalculateTags 全量重建衍生状态）
        Unit player = new Unit(data.playerName == null || data.playerName.isEmpty() ? "玩家" : data.playerName,
                UnitRole.PLAYER, tagFactory, effectEngine, eventBus, itemRegistry);
        restoreIdentity(player, data.currentRaceId, data.currentClassId,
                data.questTagIds, data.traitTagIds, data.level, data.exp, data.gold);
        player.setWorldPos(new MapPos(data.worldX, data.worldY));

        // 2. 重建生存状态（注入存档值 + 幂等惩罚Buff）
        player.survival().restore(data.hunger, data.thirst, data.temperature, data.sanity);
        player.survival().applyPenalties();
        try {
            player.setDifficulty(DifficultyMode.parse(data.difficulty));
        } catch (IllegalArgumentException ignored) {
            player.setDifficulty(DifficultyMode.NORMAL);
        }
        if (time != null) time.restore(data.gameDay, data.gameHour);

        // 3. 重建背包
        player.inventory().loadFrom(data.inventory);

        // 4. 重建世界（层级 + 迷雾）
        WorldMap map = new WorldMap(worldWidth, worldHeight);
        try {
            map.switchLayer(MapLayer.parse(data.mapLayer));
        } catch (IllegalArgumentException ignored) {
            // 未知层级保持地表
        }
        map.currentFog().importRows(data.fogOfWar);

        // 5. 重建队友（两遍：先建实例，再按名字恢复队友间好感度矩阵）
        List<Unit> companions = new ArrayList<>();
        for (CompanionSaveData csd : data.companions) {
            Unit companion = new Unit(csd.name, UnitRole.ALLY, tagFactory, effectEngine, eventBus, itemRegistry);
            restoreIdentity(companion, csd.currentRaceId, csd.currentClassId,
                    csd.questTagIds, csd.traitTagIds, csd.level, csd.exp, 0);
            companion.setAffinity(csd.affinity);
            companion.setMercenary("Mercenary".equals(csd.recruitmentType));
            companion.setContractDuration(companion.isMercenary() ? csd.contractRemaining : 0);
            companion.inventory().loadFrom(csd.inventory);
            companions.add(companion);
        }
        for (int i = 0; i < companions.size(); i++) {
            CompanionSaveData csd = data.companions.get(i);
            for (var kv : csd.allyAffinities.entrySet()) {
                Unit other = companions.stream()
                        .filter(c -> c.name().equals(kv.getKey()))
                        .findFirst().orElse(null);
                if (other != null) companions.get(i).allyAffinities().put(other, kv.getValue());
            }
        }

        // 6. 全部就绪 → 发射加载完成事件
        eventBus.emit(EventTypes.GAME_LOADED, player);

        return new GameState(player, map, companions,
                data.npcAffinities, data.factionReputations, data.quests, data.worldFlags,
                data.playTime, data.quickBar, data.lockedItems, data.stepCount,
                data.gameDay, data.gameHour, data.mapLayer, data.biomeId);
    }

    /** 恢复身份源头数据：标签先入集合，种族/职业切换触发最终全量重建。 */
    private void restoreIdentity(Unit unit, String raceId, String classId,
                                 Set<String> questTags, Set<String> traitTags,
                                 int level, int exp, int gold) {
        unit.questTagIds().addAll(questTags);
        unit.traitTagIds().addAll(traitTags);
        unit.setLevel(level);
        unit.setExp(exp);
        unit.setGold(gold);
        if (raceId != null && !raceId.isEmpty() && raceGraph.getNode(raceId) instanceof RaceNode race) {
            unit.changeRace(race);
        }
        if (classId != null && !classId.isEmpty() && classGraph.getNode(classId) instanceof ClassNode cls) {
            unit.changeClass(cls);
        }
        unit.recalculateTags();
    }
}

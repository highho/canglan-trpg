package com.canglan.save;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.world.WorldMap;
import com.canglan.world.unit.Unit;

/** 读档后的完整游戏状态。对应 C# GameState（装备/家园字段留待后续阶段）。 */
public record GameState(
        Unit player,
        WorldMap map,
        List<Unit> companions,
        Map<String, Integer> npcAffinities,
        Map<String, Map<String, Integer>> factionReputations,
        Map<String, QuestSaveData> quests,
        Map<String, Boolean> worldFlags,
        long playTime,
        List<String> quickBar,
        Set<String> lockedItems,
        int stepCount,
        int gameDay,
        int gameHour,
        String mapLayer,
        String biomeId) {
}

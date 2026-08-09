package com.canglan.world;

import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.world.unit.Unit;

/**
 * SurvivalManager — 生存系统统筹。对应 C# SurvivalManager。
 * 玩家移动后 tick 生存数值 → 更新迷雾 → 施加惩罚 → 发射 PLAYER_MOVED。
 */
public final class SurvivalManager {

    private final EventBus eventBus;

    public SurvivalManager(EventBus bus) {
        this.eventBus = bus;
    }

    /** 玩家移动后触发（time 提供季节与难度消耗，可为 null）。 */
    public void onPlayerMove(Unit player, WorldMap map, GameTime time) {
        Season season = time != null ? time.season() : Season.SPRING;
        double consumeMul = DifficultySettings.get(player.difficulty()).consumeMul();
        player.survival().tick(map.currentBiome(player.worldPos()), season, consumeMul);
        map.currentFog().decayAfterMove(player);
        map.currentFog().update(player);
        player.survival().applyPenalties();
        player.recalculateTags();   // 生存标签变化 → 重建
        eventBus.emit(EventTypes.PLAYER_MOVED, player);
    }
}

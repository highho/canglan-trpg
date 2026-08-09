package com.canglan.save;

import java.util.function.Supplier;

import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;

/**
 * AutoSaveTrigger — 自动保存触发点：战斗结束/任务完成/每50次移动/进入新区域/退出游戏。
 * 对应 C# AutoSaveTrigger。
 */
public final class AutoSaveTrigger {

    public static final int AUTO_SLOT = 0;
    private static final int AUTO_SAVE_INTERVAL = 50;

    private final SaveManager saveManager;
    private final Supplier<SaveData> capture;
    private int movesSinceLastSave;

    public AutoSaveTrigger(EventBus eventBus, SaveManager saveManager, Supplier<SaveData> capture) {
        this.saveManager = saveManager;
        this.capture = capture;

        // 战斗结束后
        eventBus.subscribeWithOwner(EventTypes.BATTLE_END, e -> doAutoSave(), this);
        // 关键任务完成
        eventBus.subscribeWithOwner(EventTypes.QUEST_COMPLETED, e -> doAutoSave(), this);
        // 定时保存（每N次大地图移动）
        eventBus.subscribeWithOwner(EventTypes.PLAYER_MOVED, e -> {
            if (++movesSinceLastSave >= AUTO_SAVE_INTERVAL) {
                doAutoSave();
                movesSinceLastSave = 0;
            }
        }, this);
        // 进入新区域
        eventBus.subscribeWithOwner(EventTypes.AREA_ENTERED, e -> doAutoSave(), this);
        // 退出游戏
        eventBus.subscribeWithOwner(EventTypes.GAME_QUIT, e -> doAutoSave(), this);
    }

    private void doAutoSave() {
        saveManager.autoSave(AUTO_SLOT, capture.get());
    }
}

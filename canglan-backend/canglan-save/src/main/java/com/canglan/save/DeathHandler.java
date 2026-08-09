package com.canglan.save;

import com.canglan.world.unit.Unit;

/**
 * DeathHandler — 玩家死亡与存档的交互。对应 C# DeathHandler。
 * PERMADEATH → 删除当前存档回主菜单；RELOAD → 强制读取最近存档；
 * PENALTY → 扣金币/经验，原地复活（不读档，保留进度）。
 */
public final class DeathHandler {

    private DeathMode mode;
    private final SaveManager saveManager;
    private final int slot;

    public DeathHandler(DeathMode mode, SaveManager saveManager) {
        this(mode, saveManager, AutoSaveTrigger.AUTO_SLOT);
    }

    public DeathHandler(DeathMode mode, SaveManager saveManager, int slot) {
        this.mode = mode;
        this.saveManager = saveManager;
        this.slot = slot;
    }

    public DeathMode mode() { return mode; }
    public void setMode(DeathMode v) { this.mode = v; }

    /** 处理玩家死亡，返回后续流程指示。 */
    public DeathOutcome handleDeath(Unit player) {
        return switch (mode) {
            case PERMADEATH -> {
                saveManager.delete(slot);          // 删档 → 回到主菜单
                yield DeathOutcome.SAVE_DELETED;
            }
            case RELOAD -> DeathOutcome.RELOAD_REQUIRED;   // 显示死亡画面 → 强制读档
            case PENALTY -> {
                player.setGold(player.gold() / 2);         // 金币减半
                player.setExp((int) (player.exp() * 0.8)); // 经验×0.8
                player.revive(0.5f);                       // 半血原地复活
                yield DeathOutcome.REVIVED;
            }
        };
    }
}

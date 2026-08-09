package com.canglan.core.eventbus;

/** 事件类型全集常量。对应 C# EventTypes。 */
public final class EventTypes {
    private EventTypes() {}

    // 战斗
    public static final String BATTLE_START = "BATTLE_START";
    public static final String TURN_START = "TURN_START";
    public static final String ACTION_EXECUTED = "ACTION_EXECUTED";
    public static final String DAMAGE_DEALT = "DAMAGE_DEALT";
    public static final String DAMAGE_CRIT = "DAMAGE_CRIT";
    public static final String HP_BELOW_30 = "HP_BELOW_30";
    public static final String UNIT_DEATH = "UNIT_DEATH";
    public static final String TURN_END = "TURN_END";
    public static final String BATTLE_END = "BATTLE_END";
    public static final String SKILL_USED = "SKILL_USED";
    public static final String SKILL_FAILED = "SKILL_FAILED";
    public static final String ALLY_COVER = "ALLY_COVER";
    public static final String ALLY_BETRAY = "ALLY_BETRAY";

    // Buff
    public static final String BUFF_APPLIED = "BUFF_APPLIED";
    public static final String BUFF_REMOVED = "BUFF_REMOVED";
    public static final String BUFF_EXPIRED = "BUFF_EXPIRED";

    // 情感
    public static final String ALLY_DEATH = "ALLY_DEATH";
    public static final String SURROUNDED = "SURROUNDED";
    public static final String ENEMY_KILLED = "ENEMY_KILLED";

    // 系统
    public static final String TAG_CHANGED = "TAG_CHANGED";
    public static final String RACE_CHANGED = "RACE_CHANGED";
    public static final String CLASS_CHANGED = "CLASS_CHANGED";
    public static final String QUEST_COMPLETED = "QUEST_COMPLETED";
    public static final String NPC_INTERACTION = "NPC_INTERACTION";
    public static final String ITEM_ACQUIRED = "ITEM_ACQUIRED";
    public static final String ITEM_USED = "ITEM_USED";
    public static final String EQUIP_BROKEN = "EQUIP_BROKEN";
    public static final String GAME_LOADED = "GAME_LOADED";
    public static final String CONTRACT_EXPIRED = "CONTRACT_EXPIRED";
    public static final String HOME_LEVEL_UP = "HOME_LEVEL_UP";
    public static final String PLAYER_MOVED = "PLAYER_MOVED";
    public static final String ACHIEVEMENT_UNLOCKED = "ACHIEVEMENT_UNLOCKED";
    public static final String AREA_ENTERED = "AREA_ENTERED";
    public static final String GAME_QUIT = "GAME_QUIT";

    // 生存
    public static final String HUNGER_CRITICAL = "HUNGER_CRITICAL";
    public static final String THIRST_CRITICAL = "THIRST_CRITICAL";
    public static final String TEMP_CRITICAL = "TEMP_CRITICAL";
}

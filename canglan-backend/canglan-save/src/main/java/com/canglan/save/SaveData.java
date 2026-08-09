package com.canglan.save;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.world.FogRow;

/**
 * SaveData — 存档数据结构。对应 C# SaveData。
 * 无状态重建架构下的极致轻量存档：
 * 只存源头数据（race/class/tags/inventory），加载时 recalculateTags() 全量重构。
 * P4 阶段：迷雾（fogOfWar）已接回；家园/AI记忆/生涯统计 留待对应阶段迁移后补充。
 */
public final class SaveData {

    // === 元信息 ===
    public int version;
    public long timestamp;
    public long playTime;
    public String locationName;

    // === 玩家身份（源头数据） ===
    public String playerName;
    public String currentRaceId;
    public String currentClassId;
    public Set<String> questTagIds = new HashSet<>();
    public Set<String> traitTagIds = new HashSet<>();
    public int level;
    public int exp;
    public int gold;

    // === 生存状态 ===
    public int hunger = 100;
    public int thirst = 100;
    public int temperature = 100;
    public int sanity = 100;

    // === 游戏时间（开拓者式时间栏） ===
    public int gameDay = 1;
    public int gameHour = 6;

    // === 难度 ===
    public String difficulty = "NORMAL";

    // === 世界位置 ===
    public int worldX;
    public int worldY;
    public String mapLayer = "Surface";
    public String biomeId = "Plains";

    // === 战争迷雾（每行 U/E/V 字符序列，P4 接回） ===
    public List<FogRow> fogOfWar = new ArrayList<>();

    // === 背包 ===
    public Map<String, Integer> inventory = new HashMap<>();

    // === 快捷栏（开拓者式 7 格，存物品ID，空=""） ===
    public List<String> quickBar = new ArrayList<>();

    // === 物品锁定（背包中不可丢弃/出售的物品ID集合） ===
    public Set<String> lockedItems = new HashSet<>();

    // === 装备（slotName → equipId） ===
    public Map<String, String> equippedItems = new HashMap<>();

    // === 家园 ===
    public HomeSaveData home;

    // === NPC 关系 / 声望 ===
    public Map<String, Integer> npcAffinities = new HashMap<>();                  // npcId → 好感度
    public Map<String, Map<String, Integer>> factionReputations = new HashMap<>(); // factionId → { unitId → 声望 }

    // === 队友 ===
    public List<CompanionSaveData> companions = new ArrayList<>();

    // === 任务进度 ===
    public Map<String, QuestSaveData> quests = new HashMap<>();

    // === 全局标记 ===
    public Map<String, Boolean> worldFlags = new HashMap<>();

    // === 步数 ===
    public int stepCount;
}

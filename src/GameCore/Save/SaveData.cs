using GameCore.World;

namespace GameCore.Save;

/// <summary>
/// SaveData — 存档数据结构。无状态重建架构下的极致轻量存档：
/// 只存源头数据（race/class/tags/inventory），加载时 recalculateTags() 全量重构。
/// </summary>
public sealed class SaveData
{
    // === 元信息 ===
    public int Version { get; set; }
    public long Timestamp { get; set; }
    public long PlayTime { get; set; }
    public string LocationName { get; set; }

    // === 玩家身份（源头数据） ===
    public string PlayerName { get; set; }
    public string CurrentRaceId { get; set; }
    public string CurrentClassId { get; set; }
    public HashSet<string> QuestTagIds { get; set; } = new();
    public HashSet<string> TraitTagIds { get; set; } = new();
    public int Level { get; set; }
    public int Exp { get; set; }
    public int Gold { get; set; }

    // === 生存状态 ===
    public int Hunger { get; set; } = 100;
    public int Thirst { get; set; } = 100;
    public int Temperature { get; set; } = 100;

    // === 世界位置 ===
    public int WorldX { get; set; }
    public int WorldY { get; set; }
    public string MapLayer { get; set; } = "Surface";
    public string BiomeId { get; set; } = "Plains";

    // === 战争迷雾 ===
    public List<FogRow> FogOfWar { get; set; } = new();

    // === 背包 ===
    public Dictionary<string, int> Inventory { get; set; } = new();

    // === 装备（slotName → equipId） ===
    public Dictionary<string, string> EquippedItems { get; set; } = new();

    // === 家园 ===
    public HomeSaveData Home { get; set; }

    // === NPC 关系 / 声望 ===
    public Dictionary<string, int> NpcAffinities { get; set; } = new();        // npcId → 好感度
    public Dictionary<string, Dictionary<string, int>> FactionReputations { get; set; } = new(); // factionId → { unitId → 声望 }

    // === 队友 ===
    public List<CompanionSaveData> Companions { get; set; } = new();

    // === 任务进度 ===
    public Dictionary<string, QuestSaveData> Quests { get; set; } = new();

    // === 全局标记 ===
    public Dictionary<string, bool> WorldFlags { get; set; } = new();
}

/// <summary>家园存档。</summary>
public sealed class HomeSaveData
{
    public int Level { get; set; }
    public int X { get; set; }
    public int Y { get; set; }
    public int GridWidth { get; set; }
    public int GridHeight { get; set; }
    public List<BuildingSaveData> Buildings { get; set; } = new();
}

/// <summary>建筑存档。</summary>
public sealed class BuildingSaveData
{
    public string BuildingId { get; set; }
    public int GridX { get; set; }
    public int GridY { get; set; }
    public int Level { get; set; }
    public string State { get; set; }          // "Blueprint" / "Constructing" / "Complete"
    public int BuildProgress { get; set; }
}

/// <summary>队友存档（源头数据 → recalculateTags 重建）。</summary>
public sealed class CompanionSaveData
{
    public string Name { get; set; }
    public string CurrentRaceId { get; set; }
    public string CurrentClassId { get; set; }
    public HashSet<string> QuestTagIds { get; set; } = new();
    public HashSet<string> TraitTagIds { get; set; } = new();
    public int Level { get; set; } = 1;
    public int Exp { get; set; }
    public string RecruitmentType { get; set; } = "Bond";   // "Bond" / "Mercenary"
    public int ContractRemaining { get; set; } = -1;        // 感情招募=-1
    public int Affinity { get; set; }                       // 对玩家好感度
    public Dictionary<string, int> AllyAffinities { get; set; } = new();   // 队友名 → 好感度
    public Dictionary<string, int> Inventory { get; set; } = new();
}

/// <summary>任务进度存档。</summary>
public sealed class QuestSaveData
{
    public string QuestId { get; set; }
    public int CurrentStep { get; set; }
    public Dictionary<string, bool> Flags { get; set; } = new();
    public int CooldownRemaining { get; set; }
}

/// <summary>存档槽位信息。</summary>
public sealed record SaveSlotInfo(int Slot, long Timestamp, long PlayTime, string Location, int Level);

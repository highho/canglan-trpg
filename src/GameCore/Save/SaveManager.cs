using System.Text.Json;
using GameCore.Effect;
using GameCore.EventBus;
using GameCore.Graph;
using GameCore.World;

namespace GameCore.Save;

/// <summary>
/// SaveManager — 存档管理。核心洞察：recalculateTags() 是无状态函数，
/// 存档只保存「输入」（源头数据），不保存「输出」（衍生状态）→ 文件极小、迁移安全。
/// </summary>
public sealed class SaveManager
{
    public const int MaxSlots = 10;
    public const int CurrentVersion = 1;

    private readonly string _saveDir;
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true
    };

    public SaveManager(string saveDir = "saves")
    {
        _saveDir = saveDir;
        Directory.CreateDirectory(_saveDir);
    }

    private string SlotPath(int slot) => Path.Combine(_saveDir, $"save_{slot}.json");

    // ==================== 保存 ====================

    /// <summary>从当前游戏状态构建 SaveData（源头数据采集）。</summary>
    public static SaveData Capture(
        Unit.Unit player, WorldMap map, Home.HomeBase home,
        IReadOnlyList<Unit.Unit> companions,
        Equipment.EquipmentManager equipment,
        Dictionary<string, int> npcAffinities,
        Dictionary<string, Dictionary<string, int>> factionReputations,
        Dictionary<string, QuestSaveData> quests,
        Dictionary<string, bool> worldFlags,
        long playTime, string locationName)
    {
        var data = new SaveData
        {
            Version = CurrentVersion,
            Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            PlayTime = playTime,
            LocationName = locationName,

            // 玩家身份（源头数据）
            PlayerName = player.Name,
            CurrentRaceId = player.CurrentRace?.Id,
            CurrentClassId = player.CurrentClass?.Id,
            QuestTagIds = new HashSet<string>(player.QuestTagIds),
            TraitTagIds = new HashSet<string>(player.TraitTagIds),
            Level = player.Level,
            Exp = player.Exp,
            Gold = player.Gold,

            // 生存状态
            Hunger = player.Survival.Hunger,
            Thirst = player.Survival.Thirst,
            Temperature = player.Survival.Temperature,

            // 世界位置
            WorldX = player.WorldPos?.X ?? 0,
            WorldY = player.WorldPos?.Y ?? 0,
            MapLayer = map.CurrentLayer.ToString(),
            BiomeId = map.CurrentBiome(player.WorldPos).ToString(),

            // 迷雾（当前层压缩导出）
            FogOfWar = map.CurrentFog().ExportRows(),

            // 背包 / 装备
            Inventory = player.Inventory.ToSaveMap(),
            EquippedItems = equipment?.GetEquippedMap() ?? new Dictionary<string, string>(),

            // 家园
            Home = CaptureHome(home),

            // NPC关系 / 声望 / 队友 / 任务 / 全局标记
            NpcAffinities = npcAffinities ?? new Dictionary<string, int>(),
            FactionReputations = factionReputations ?? new Dictionary<string, Dictionary<string, int>>(),
            Companions = companions?.Select(CaptureCompanion).ToList() ?? new List<CompanionSaveData>(),
            Quests = quests ?? new Dictionary<string, QuestSaveData>(),
            WorldFlags = worldFlags ?? new Dictionary<string, bool>()
        };
        return data;
    }

    private static HomeSaveData CaptureHome(Home.HomeBase home)
    {
        if (home == null) return null;
        var hs = new HomeSaveData
        {
            Level = home.Level,
            X = home.Position?.X ?? 0,
            Y = home.Position?.Y ?? 0,
            GridWidth = home.GridWidth,
            GridHeight = home.GridHeight
        };
        for (var y = 0; y < home.GridHeight; y++)
            for (var x = 0; x < home.GridWidth; x++)
            {
                var b = home.GetAt(x, y);
                if (b == null) continue;
                hs.Buildings.Add(new BuildingSaveData
                {
                    BuildingId = b.Id,
                    GridX = x,
                    GridY = y,
                    Level = b.Level,
                    State = b.State.ToString(),
                    BuildProgress = b.BuildProgress
                });
            }
        return hs;
    }

    private static CompanionSaveData CaptureCompanion(Unit.Unit c) => new()
    {
        Name = c.Name,
        CurrentRaceId = c.CurrentRace?.Id,
        CurrentClassId = c.CurrentClass?.Id,
        QuestTagIds = new HashSet<string>(c.QuestTagIds),
        TraitTagIds = new HashSet<string>(c.TraitTagIds),
        Level = c.Level,
        Exp = c.Exp,
        RecruitmentType = c.IsMercenary ? "Mercenary" : "Bond",
        ContractRemaining = c.IsMercenary ? c.ContractDuration : -1,
        Affinity = c.Affinity,
        AllyAffinities = c.AllyAffinities.ToDictionary(kv => kv.Key.Name, kv => kv.Value),
        Inventory = c.Inventory.ToSaveMap()
    };

    /// <summary>写入磁盘。</summary>
    public bool Save(int slot, SaveData data)
    {
        if (slot < 0 || slot >= MaxSlots) return false;
        try
        {
            File.WriteAllText(SlotPath(slot), JsonSerializer.Serialize(data, JsonOpts));
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>自动保存（指定槽位）+ AUTO_SAVE 事件提示由触发器负责。</summary>
    public bool AutoSave(int slot, SaveData data) => Save(slot, data);

    // ==================== 读取 ====================

    /// <summary>读取存档文件（不存在返回 null）。</summary>
    public SaveData Load(int slot)
    {
        var path = SlotPath(slot);
        if (slot < 0 || slot >= MaxSlots || !File.Exists(path)) return null;
        try
        {
            return JsonSerializer.Deserialize<SaveData>(File.ReadAllText(path));
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>列出所有存档槽位。</summary>
    public List<SaveSlotInfo> ListSlots()
    {
        var slots = new List<SaveSlotInfo>();
        for (var i = 0; i < MaxSlots; i++)
        {
            var data = Load(i);
            if (data != null)
                slots.Add(new SaveSlotInfo(i, data.Timestamp, data.PlayTime, data.LocationName, data.Level));
        }
        return slots;
    }

    /// <summary>删除存档（硬核模式死亡时调用）。</summary>
    public bool Delete(int slot)
    {
        var path = SlotPath(slot);
        if (!File.Exists(path)) return false;
        try
        {
            File.Delete(path);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    // ==================== 版本迁移 ====================

    /// <summary>加载时自动迁移旧版本存档（逐版本推进，最后统一版本号）。</summary>
    public SaveData Migrate(SaveData old)
    {
        if (old == null || old.Version == CurrentVersion) return old;
        var current = old;
        // if (current.Version < 2) current = MigrateV1ToV2(current);
        // if (current.Version < 3) current = MigrateV2ToV3(current);
        current.Version = CurrentVersion;
        return current;
    }
}

/// <summary>读档后的完整游戏状态。</summary>
public sealed record GameState(
    Unit.Unit Player,
    WorldMap Map,
    Home.HomeBase Home,
    List<Unit.Unit> Companions,
    Equipment.EquipmentManager Equipment,
    Dictionary<string, int> NpcAffinities,
    Dictionary<string, Dictionary<string, int>> FactionReputations,
    Dictionary<string, QuestSaveData> Quests,
    Dictionary<string, bool> WorldFlags,
    long PlayTime);

/// <summary>
/// GameLoader — 加载流程：读档 → 迁移 → 重建玩家（recalculateTags 全量重建）
/// → 生存/背包/装备/世界/家园/队友 → GAME_LOADED 事件。
/// </summary>
public sealed class GameLoader
{
    private readonly SaveManager _saveManager;
    private readonly GraphEngine<RaceData> _raceGraph;
    private readonly GraphEngine<ClassData> _classGraph;
    private readonly Tag.TagFactory _tagFactory;
    private readonly EffectEngine _effectEngine;
    private readonly IEventBus _eventBus;
    private readonly int _worldWidth;
    private readonly int _worldHeight;

    public GameLoader(SaveManager saveManager, GraphEngine<RaceData> raceGraph,
        GraphEngine<ClassData> classGraph, Tag.TagFactory tagFactory,
        EffectEngine effectEngine, IEventBus eventBus, int worldWidth, int worldHeight)
    {
        _saveManager = saveManager;
        _raceGraph = raceGraph;
        _classGraph = classGraph;
        _tagFactory = tagFactory;
        _effectEngine = effectEngine;
        _eventBus = eventBus;
        _worldWidth = worldWidth;
        _worldHeight = worldHeight;
    }

    public GameState Load(int slot)
    {
        var data = _saveManager.Load(slot);
        if (data == null) throw new InvalidOperationException($"存档不存在: slot {slot}");
        data = _saveManager.Migrate(data);

        // 1. 重建玩家（源头数据 → recalculateTags 全量重建衍生状态）
        var player = new Unit.Unit(string.IsNullOrEmpty(data.PlayerName) ? "玩家" : data.PlayerName,
            Unit.UnitRole.Player, _tagFactory, _effectEngine, _eventBus);
        RestoreIdentity(player, data.CurrentRaceId, data.CurrentClassId,
            data.QuestTagIds, data.TraitTagIds, data.Level, data.Exp, data.Gold);
        player.WorldPos = new MapPos(data.WorldX, data.WorldY);

        // 2. 重建生存状态（注入存档值 + 幂等惩罚Buff）
        player.Survival.Restore(data.Hunger, data.Thirst, data.Temperature);
        player.Survival.ApplyPenalties();

        // 3. 重建背包
        player.Inventory.LoadFrom(data.Inventory);

        // 4. 重建装备（走正常 Equip 流程重建永久Buff）
        var equipment = new Equipment.EquipmentManager(player, _eventBus);
        equipment.RestoreEquipped(data.EquippedItems.Values);

        // 5. 重建世界（层级 + 迷雾）
        var map = new WorldMap(_worldWidth, _worldHeight);
        if (Enum.TryParse<MapLayer>(data.MapLayer, out var layer)) map.SwitchLayer(layer);
        map.CurrentFog().ImportRows(data.FogOfWar);

        // 6. 重建家园
        var home = LoadHome(data.Home, player);

        // 7. 重建队友（两遍：先建实例，再按名字恢复队友间好感度矩阵）
        var companions = new List<Unit.Unit>();
        foreach (var csd in data.Companions)
        {
            var companion = new Unit.Unit(csd.Name, Unit.UnitRole.Ally, _tagFactory, _effectEngine, _eventBus);
            RestoreIdentity(companion, csd.CurrentRaceId, csd.CurrentClassId,
                csd.QuestTagIds, csd.TraitTagIds, csd.Level, csd.Exp, gold: 0);
            companion.Affinity = csd.Affinity;
            companion.IsMercenary = csd.RecruitmentType == "Mercenary";
            companion.ContractDuration = companion.IsMercenary ? csd.ContractRemaining : 0;
            companion.Inventory.LoadFrom(csd.Inventory);
            companions.Add(companion);
        }
        for (var i = 0; i < companions.Count; i++)
        {
            var csd = data.Companions[i];
            foreach (var kv in csd.AllyAffinities)
            {
                var other = companions.FirstOrDefault(c => c.Name == kv.Key);
                if (other != null) companions[i].AllyAffinities[other] = kv.Value;
            }
        }

        // 8. 全部就绪 → 发射加载完成事件
        _eventBus.Emit(EventTypes.GameLoaded, player);

        return new GameState(player, map, home, companions, equipment,
            data.NpcAffinities, data.FactionReputations, data.Quests, data.WorldFlags, data.PlayTime);
    }

    /// <summary>恢复身份源头数据：标签先入集合，种族/职业切换触发最终全量重建。</summary>
    private void RestoreIdentity(Unit.Unit unit, string raceId, string classId,
        HashSet<string> questTags, HashSet<string> traitTags, int level, int exp, int gold)
    {
        unit.QuestTagIds.UnionWith(questTags);
        unit.TraitTagIds.UnionWith(traitTags);
        unit.Level = level;
        unit.Exp = exp;
        unit.Gold = gold;
        if (!string.IsNullOrEmpty(raceId)) unit.ChangeRace(_raceGraph.GetNode(raceId) as RaceNode);
        if (!string.IsNullOrEmpty(classId)) unit.ChangeClass(_classGraph.GetNode(classId) as ClassNode);
        unit.RecalculateTags();
    }

    private Home.HomeBase LoadHome(HomeSaveData hs, Unit.Unit owner)
    {
        if (hs == null) return null;
        var home = new Home.HomeBase(new MapPos(hs.X, hs.Y), hs.GridWidth, hs.GridHeight, _eventBus, owner);
        home.RestoreLevel(hs.Level);
        var registry = Home.BuildingRegistry.Instance;
        if (registry == null) return home;
        foreach (var bsd in hs.Buildings)
        {
            if (!registry.TryCreate(bsd.BuildingId, out var building)) continue;
            if (Enum.TryParse<Home.BuildingState>(bsd.State, out var state))
                building.RestoreState(state, bsd.BuildProgress);
            building.Level = bsd.Level;
            home.RestoreBuilding(building, bsd.GridX, bsd.GridY);
        }
        return home;
    }
}

/// <summary>
/// AutoSaveTrigger — 自动保存触发点：战斗结束/任务完成/每50次移动/进入新区域/退出游戏。
/// </summary>
public sealed class AutoSaveTrigger
{
    public const int AutoSlot = 0;
    private const int AutoSaveInterval = 50;

    private readonly SaveManager _saveManager;
    private readonly Func<SaveData> _capture;
    private int _movesSinceLastSave;

    public AutoSaveTrigger(IEventBus eventBus, SaveManager saveManager, Func<SaveData> capture)
    {
        _saveManager = saveManager;
        _capture = capture;

        // 战斗结束后
        eventBus.SubscribeWithOwner(EventTypes.BattleEnd, _ => DoAutoSave(), this);
        // 关键任务完成
        eventBus.SubscribeWithOwner(EventTypes.QuestCompleted, _ => DoAutoSave(), this);
        // 定时保存（每N次大地图移动）
        eventBus.SubscribeWithOwner(EventTypes.PlayerMoved, _ =>
        {
            if (++_movesSinceLastSave >= AutoSaveInterval)
            {
                DoAutoSave();
                _movesSinceLastSave = 0;
            }
        }, this);
        // 进入新区域
        eventBus.SubscribeWithOwner(EventTypes.AreaEntered, _ => DoAutoSave(), this);
        // 退出游戏
        eventBus.SubscribeWithOwner(EventTypes.GameQuit, _ => DoAutoSave(), this);
    }

    private void DoAutoSave() => _saveManager.AutoSave(AutoSlot, _capture());
}

/// <summary>死亡模式（定义在存档系统的配置中，而非独立系统）。</summary>
public enum DeathMode
{
    Permadeath,   // 硬核：删档
    Reload,       // 普通：强制读档
    Penalty       // 轻度：扣金币/经验原地复活
}

/// <summary>死亡处理结果。</summary>
public enum DeathOutcome { SaveDeleted, ReloadRequired, Revived }

/// <summary>
/// DeathHandler — 玩家死亡与存档的交互。
/// PERMADEATH → 删除当前存档回主菜单；RELOAD → 强制读取最近存档；
/// PENALTY → 扣金币/经验，原地复活（不读档，保留进度）。
/// </summary>
public sealed class DeathHandler
{
    public DeathMode Mode { get; set; }
    private readonly SaveManager _saveManager;
    private readonly int _slot;

    public DeathHandler(DeathMode mode, SaveManager saveManager, int slot = AutoSaveTrigger.AutoSlot)
    {
        Mode = mode;
        _saveManager = saveManager;
        _slot = slot;
    }

    /// <summary>处理玩家死亡，返回后续流程指示。</summary>
    public DeathOutcome HandleDeath(Unit.Unit player)
    {
        switch (Mode)
        {
            case DeathMode.Permadeath:
                _saveManager.Delete(_slot);          // 删档 → 回到主菜单
                return DeathOutcome.SaveDeleted;

            case DeathMode.Reload:
                return DeathOutcome.ReloadRequired;  // 显示死亡画面 → 强制读档

            case DeathMode.Penalty:
            default:
                player.Gold /= 2;                    // 金币减半
                player.Exp = (int)(player.Exp * 0.8);
                player.Revive(0.5f);                 // 半血原地复活
                return DeathOutcome.Revived;
        }
    }
}

using System.Text.Json;
using GameCore.EventBus;

namespace GameCore.Home;

/// <summary>建筑分类。</summary>
public enum BuildingCategory { Storage, Crafting, Rest, Farm, Defense, Utility }

/// <summary>建筑状态。</summary>
public enum BuildingState { Blueprint, Constructing, Complete }

/// <summary>建筑效果类型。</summary>
public enum BuildingEffectType
{
    StorageBonus,    // 增加背包容量
    CraftUnlock,     // 解锁新配方
    HealRate,        // 休息恢复速率
    DefenseBonus,    // 防御墙减伤
    FarmYield,       // 农田产量
    NpcAttract       // 吸引NPC来访概率
}

/// <summary>建筑建造条件。</summary>
public sealed record BuildingRequirement(
    Dictionary<string, int> Materials,    // { "wood": 10, "iron_ingot": 3 }
    Tag.ITagCondition TagCondition,       // [锻造Lv2] 才能建工作台
    string PrerequisiteBuildingId);       // 前置建筑ID，null=无

/// <summary>建筑建成后效果。</summary>
public sealed record BuildingEffect(BuildingEffectType Type, Dictionary<string, object> Params);

/// <summary>
/// Building — 建筑实例。投入材料推进建造，进度达标 → COMPLETE。
/// </summary>
public sealed class Building
{
    public string Id { get; }
    public string Name { get; }
    public BuildingCategory Category { get; }
    public BuildingState State { get; private set; } = BuildingState.Blueprint;
    public int BuildProgress { get; private set; }
    public int BuildTotal { get; }
    public BuildingRequirement Requirement { get; }
    public BuildingEffect Effect { get; }
    public int Level { get; set; } = 1;

    public Building(string id, string name, BuildingCategory category,
        BuildingRequirement requirement, BuildingEffect effect)
    {
        Id = id;
        Name = name;
        Category = category;
        Requirement = requirement;
        Effect = effect;
        BuildTotal = requirement.Materials.Values.Sum();
    }

    /// <summary>投入材料推进建造。</summary>
    public bool Contribute(string materialId, int quantity)
    {
        if (State == BuildingState.Complete) return false;
        if (!Requirement.Materials.ContainsKey(materialId)) return false;

        BuildProgress += quantity;
        if (BuildProgress >= BuildTotal)
        {
            State = BuildingState.Complete;
            BuildProgress = BuildTotal;
        }
        else
        {
            State = BuildingState.Constructing;
        }
        return true;
    }

    public bool IsComplete => State == BuildingState.Complete;

    /// <summary>读档恢复建造状态。</summary>
    public void RestoreState(BuildingState state, int progress)
    {
        State = state;
        BuildProgress = Math.Min(progress, BuildTotal);
    }
}

/// <summary>建筑蓝图注册表（buildings.json）。</summary>
public sealed class BuildingRegistry
{
    public static BuildingRegistry Instance { get; private set; }

    private readonly Dictionary<string, (string Name, BuildingCategory Category,
        BuildingRequirement Requirement, BuildingEffect Effect)> _defs = new();

    public void Load(string jsonPath) => LoadFromText(File.ReadAllText(jsonPath));

    public void LoadFromText(string json)
    {
        var parser = new Tag.TagConditionParser();
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var n = prop.Value;
            var materials = new Dictionary<string, int>();
            if (n.TryGetProperty("materials", out var ms))
                foreach (var m in ms.EnumerateObject())
                    materials[m.Name] = m.Value.GetInt32();

            var condText = n.TryGetProperty("condition", out var c) ? c.GetString() : null;
            var prereq = n.TryGetProperty("prerequisite", out var p) ? p.GetString() : null;
            var requirement = new BuildingRequirement(materials,
                string.IsNullOrEmpty(condText) ? new Tag.AlwaysTrue() : parser.Parse(condText), prereq);

            var category = (n.TryGetProperty("category", out var cat) ? cat.GetString() : "UTILITY")
                .ToUpperInvariant() switch
            {
                "STORAGE" => BuildingCategory.Storage,
                "CRAFTING" => BuildingCategory.Crafting,
                "REST" => BuildingCategory.Rest,
                "FARM" => BuildingCategory.Farm,
                "DEFENSE" => BuildingCategory.Defense,
                _ => BuildingCategory.Utility
            };

            BuildingEffect effect = null;
            if (n.TryGetProperty("effect", out var ef))
            {
                var effectType = ef.TryGetProperty("type", out var et) ? et.GetString() : "";
                var type = effectType.ToUpperInvariant() switch
                {
                    "STORAGE_BONUS" => BuildingEffectType.StorageBonus,
                    "CRAFT_UNLOCK" => BuildingEffectType.CraftUnlock,
                    "HEAL_RATE" => BuildingEffectType.HealRate,
                    "DEFENSE_BONUS" => BuildingEffectType.DefenseBonus,
                    "FARM_YIELD" => BuildingEffectType.FarmYield,
                    "NPC_ATTRACT" => BuildingEffectType.NpcAttract,
                    _ => BuildingEffectType.StorageBonus
                };
                var paramsDict = new Dictionary<string, object>();
                if (ef.TryGetProperty("params", out var pr))
                    foreach (var pp in pr.EnumerateObject())
                        paramsDict[pp.Name] = pp.Value.ValueKind switch
                        {
                            JsonValueKind.Number => pp.Value.GetSingle(),
                            JsonValueKind.String => pp.Value.GetString(),
                            _ => null
                        };
                effect = new BuildingEffect(type, paramsDict);
            }

            _defs[prop.Name] = (n.TryGetProperty("name", out var nm) ? nm.GetString() : prop.Name,
                category, requirement, effect);
        }
        Instance = this;
    }

    /// <summary>从蓝图创建建筑实例。</summary>
    public Building Create(string buildingId)
    {
        if (!_defs.TryGetValue(buildingId, out var def))
            throw new ArgumentException($"未知建筑: {buildingId}");
        return new Building(buildingId, def.Name, def.Category, def.Requirement, def.Effect);
    }

    public bool TryCreate(string buildingId, out Building building)
    {
        if (_defs.TryGetValue(buildingId, out var def))
        {
            building = new Building(buildingId, def.Name, def.Category, def.Requirement, def.Effect);
            return true;
        }
        building = null;
        return false;
    }

    public IEnumerable<string> GetAllIds() => _defs.Keys;
}

/// <summary>
/// HomeBase — 家园。大地图位置 + 内部网格放置建筑 + 家园等级（[家园LvN] 标签）。
/// </summary>
public sealed class HomeBase
{
    public World.MapPos Position { get; }
    public int GridWidth { get; }
    public int GridHeight { get; }
    public int Level { get; private set; } = 1;
    public Unit.Unit Owner { get; }

    private readonly Building[,] _grid;
    private readonly List<Building> _buildings = new();
    private readonly IEventBus _eventBus;

    public HomeBase(World.MapPos position, int width, int height, IEventBus bus, Unit.Unit owner = null)
    {
        Position = position;
        GridWidth = width;
        GridHeight = height;
        _grid = new Building[height, width];
        _eventBus = bus;
        Owner = owner;
        SyncHomeTag();
    }

    /// <summary>放置建筑蓝图（边界/占位/前置建筑检查）。</summary>
    public bool PlaceBuilding(Building building, int x, int y)
    {
        if (x < 0 || x >= GridWidth || y < 0 || y >= GridHeight) return false;
        if (_grid[y, x] != null) return false;
        if (building.Requirement.PrerequisiteBuildingId != null
            && !HasBuilding(building.Requirement.PrerequisiteBuildingId))
            return false;
        if (Owner != null && !building.Requirement.TagCondition.Evaluate(Owner.ActiveTagIds))
            return false;

        _grid[y, x] = building;
        _buildings.Add(building);
        return true;
    }

    /// <summary>读档恢复家园等级（重新同步 [家园LvN] 标签，覆盖构造时的 Lv1）。</summary>
    public void RestoreLevel(int level)
    {
        Level = Math.Max(1, level);
        SyncHomeTag();
    }

    /// <summary>读档放置建筑（跳过前置/条件检查，直接入网格）。</summary>
    public bool RestoreBuilding(Building building, int x, int y)
    {
        if (x < 0 || x >= GridWidth || y < 0 || y >= GridHeight) return false;
        if (_grid[y, x] != null) return false;
        _grid[y, x] = building;
        _buildings.Add(building);
        return true;
    }

    /// <summary>是否存在指定ID的已建成建筑。</summary>
    public bool HasBuilding(string buildingId)
        => _buildings.Any(b => b.Id == buildingId && b.IsComplete);

    /// <summary>家园等级提升 → 发射 HOME_LEVEL_UP + 更新 [家园LvN] 标签。</summary>
    public void LevelUp()
    {
        Level++;
        SyncHomeTag();
        _eventBus.Emit(EventTypes.HomeLevelUp, Owner, Level);
    }

    /// <summary>同步 [家园LvN] 特质标签（移除旧层级，加入新层级）。</summary>
    private void SyncHomeTag()
    {
        if (Owner == null) return;
        Owner.TraitTagIds.RemoveWhere(t => t.StartsWith("家园Lv"));
        Owner.TraitTagIds.Add($"家园Lv{Level}");
        Owner.RecalculateTags();
    }

    /// <summary>防御值 = 防御墙数量 × 等级 × 10。</summary>
    public int GetDefenseValue()
        => _buildings.Count(b => b.Category == BuildingCategory.Defense && b.IsComplete) * Level * 10;

    public IReadOnlyList<Building> GetBuildings() => _buildings.ToList();

    public Building GetAt(int x, int y)
        => x >= 0 && x < GridWidth && y >= 0 && y < GridHeight ? _grid[y, x] : null;
}

/// <summary>
/// HomeManager — 家园统筹：NPC来访概率 / 野外入侵概率。
/// </summary>
public sealed class HomeManager
{
    private readonly HomeBase _home;
    private readonly IEventBus _eventBus;

    public HomeManager(HomeBase home, IEventBus bus)
    {
        _home = home;
        _eventBus = bus;
    }

    /// <summary>NPC来访概率 = 基础5% + 每级2% + 建筑加成，硬上限50%。</summary>
    public float GetNpcAttraction()
    {
        var value = 0.05f + _home.Level * 0.02f;
        foreach (var b in _home.GetBuildings())
        {
            if (b.IsComplete && b.Effect?.Type == BuildingEffectType.NpcAttract
                && b.Effect.Params.TryGetValue("chance", out var chance))
                value += Convert.ToSingle(chance);
        }
        return Math.Min(0.5f, value);
    }

    /// <summary>野外入侵概率 = 基础10% - 防御值×0.1%，下限0。</summary>
    public float GetInvasionRisk()
        => Math.Max(0f, 0.1f - _home.GetDefenseValue() * 0.001f);

    /// <summary>每日结算：NPC来访/入侵判定（返回发生的事件描述列表）。</summary>
    public List<string> DailySettle(Random rng, Func<Unit.Unit> npcFactory = null)
    {
        var events = new List<string>();
        if (rng.NextDouble() < GetNpcAttraction())
        {
            events.Add("NPC_VISIT");
            _eventBus.Emit(EventTypes.NpcInteraction, _home.Owner);
        }
        if (rng.NextDouble() < GetInvasionRisk())
        {
            events.Add("INVASION");
        }
        return events;
    }
}

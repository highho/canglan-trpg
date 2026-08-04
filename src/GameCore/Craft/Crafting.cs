using System.Text.Json;

namespace GameCore.Craft;

/// <summary>资源类别。</summary>
public enum ResourceCategory { Ore, Herb, Wood, Water, Prey, Ruin }

/// <summary>可采集资源定义（resources.json）。</summary>
public sealed record GatherableResource(
    string Id,                  // "iron_ore"
    string Name,                // "铁矿脉"
    ResourceCategory Category,  // ORE / HERB / WOOD / WATER / PREY / RUIN
    int GatherDifficulty,       // 基础采集难度
    int YieldPerAction,         // 每次采集产量
    int MaxYield,               // 采集点总储量
    string RequiredTag);        // 采集需要的标签，null=无要求

/// <summary>
/// GatherPoint — 采集点实例。储量有限 + 3回合冷却；
/// 效率 = 1 + [采集LvN]层级 + 职业标签加成（矿工/草药师）。
/// </summary>
public sealed class GatherPoint
{
    public GatherableResource Resource { get; }
    public int RemainingYield { get; private set; }
    public int CooldownRemaining { get; private set; }

    public GatherPoint(GatherableResource resource)
    {
        Resource = resource;
        RemainingYield = resource.MaxYield;
    }

    /// <summary>尝试采集，返回 (物品ID, 数量)；失败返回 null。</summary>
    public (string ItemId, int Count)? Gather(Unit.Unit gatherer)
    {
        if (CooldownRemaining > 0 || RemainingYield <= 0) return null;

        // 标签检查
        if (Resource.RequiredTag != null && !gatherer.HasTag(Resource.RequiredTag))
            return null;

        // 效率修正
        var efficiency = GetGatherEfficiency(gatherer);
        var actual = Math.Min(Resource.YieldPerAction * efficiency, RemainingYield);

        RemainingYield -= actual;
        CooldownRemaining = 3;   // 3回合冷却

        return (Resource.Id, actual);
    }

    /// <summary>采集效率：基础1 + [采集LvN]层级 + 类别职业加成。</summary>
    private int GetGatherEfficiency(Unit.Unit gatherer)
    {
        var eff = 1;
        foreach (var tagId in gatherer.ActiveTagIds)
        {
            if (tagId.StartsWith("采集Lv") && int.TryParse(tagId["采集Lv".Length..], out var lv))
                eff += lv;
        }
        if (Resource.Category == ResourceCategory.Ore && gatherer.HasTag("矿工")) eff++;
        if (Resource.Category == ResourceCategory.Herb && gatherer.HasTag("草药师")) eff++;
        return eff;
    }

    public bool IsDepleted => RemainingYield <= 0;

    public void TickCooldown()
    {
        if (CooldownRemaining > 0) CooldownRemaining--;
    }
}

/// <summary>资源注册表（resources.json）。</summary>
public sealed class ResourceRegistry
{
    public static ResourceRegistry Instance { get; private set; }

    private readonly Dictionary<string, GatherableResource> _defs = new();

    public void Load(string jsonPath) => LoadFromText(File.ReadAllText(jsonPath));

    public void LoadFromText(string json)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var n = prop.Value;
            var category = (n.TryGetProperty("category", out var c) ? c.GetString() : "ORE")
                .ToUpperInvariant() switch
            {
                "HERB" => ResourceCategory.Herb,
                "WOOD" => ResourceCategory.Wood,
                "WATER" => ResourceCategory.Water,
                "PREY" => ResourceCategory.Prey,
                "RUIN" => ResourceCategory.Ruin,
                _ => ResourceCategory.Ore
            };
            Register(new GatherableResource(
                prop.Name,
                n.TryGetProperty("name", out var nm) ? nm.GetString() : prop.Name,
                category,
                n.TryGetProperty("difficulty", out var d) ? d.GetInt32() : 0,
                n.TryGetProperty("yieldPerAction", out var y) ? y.GetInt32() : 1,
                n.TryGetProperty("maxYield", out var m) ? m.GetInt32() : 10,
                n.TryGetProperty("requiredTag", out var rt) ? rt.GetString() : null));
        }
        Instance = this;
    }

    public void Register(GatherableResource r) => _defs[r.Id] = r;

    public GatherableResource Get(string id)
        => _defs.TryGetValue(id, out var r) ? r : throw new ArgumentException($"未知资源: {id}");

    public bool TryGet(string id, out GatherableResource r) => _defs.TryGetValue(id, out r);

    public IEnumerable<GatherableResource> GetAll() => _defs.Values;
}

// ==================== 制造 ====================

/// <summary>配方定义（recipes.json）。</summary>
public sealed record Recipe(
    string Id,                            // "iron_sword"
    string Name,                          // "铁剑"
    Dictionary<string, int> Materials,    // { "iron_ingot": 3, "wood": 1 }
    string OutputItemId,                  // 产出物品
    int OutputCount,                      // 产出数量
    Tag.ITagCondition UnlockCondition,    // 制造技能条件（[锻造Lv1] 等）
    int CraftTime);                       // 制造所需回合数

/// <summary>制造结果。</summary>
public sealed record CraftResult(bool Success, string OutputItemId, string Error);

/// <summary>配方注册表（全局配方，从 recipes.json 加载）。</summary>
public sealed class RecipeRegistry
{
    public static RecipeRegistry Instance { get; private set; }

    private readonly Dictionary<string, Recipe> _recipes = new();
    private readonly Tag.TagConditionParser _parser = new();

    public void Load(string jsonPath) => LoadFromText(File.ReadAllText(jsonPath));

    public void LoadFromText(string json)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var n = prop.Value;
            var materials = new Dictionary<string, int>();
            if (n.TryGetProperty("materials", out var ms))
                foreach (var m in ms.EnumerateObject())
                    materials[m.Name] = m.Value.GetInt32();

            var conditionText = n.TryGetProperty("condition", out var c) ? c.GetString() : null;
            Register(new Recipe(
                prop.Name,
                n.TryGetProperty("name", out var nm) ? nm.GetString() : prop.Name,
                materials,
                n.TryGetProperty("output", out var o) ? o.GetString() : prop.Name,
                n.TryGetProperty("outputCount", out var oc) ? oc.GetInt32() : 1,
                string.IsNullOrEmpty(conditionText) ? new Tag.AlwaysTrue() : _parser.Parse(conditionText),
                n.TryGetProperty("craftTime", out var ct) ? ct.GetInt32() : 1));
        }
        Instance = this;
    }

    public void Register(Recipe r) => _recipes[r.Id] = r;

    public Recipe Get(string id)
        => _recipes.TryGetValue(id, out var r) ? r : throw new ArgumentException($"未知配方: {id}");

    public IEnumerable<Recipe> GetAll() => _recipes.Values;
}

/// <summary>
/// CraftingSystem — 单位持有的制造系统。标签解锁配方 → 检查材料 → 执行制造。
/// 订阅 TAG_CHANGED（owner=this）：标签变化时自动解锁新配方。
/// </summary>
public sealed class CraftingSystem
{
    private readonly List<Recipe> _knownRecipes = new();
    private readonly Unit.Unit _owner;
    private readonly EventBus.IEventBus _eventBus;

    public CraftingSystem(Unit.Unit owner, EventBus.IEventBus bus)
    {
        _owner = owner;
        _eventBus = bus;
        bus.SubscribeWithOwner(EventBus.EventTypes.TagChanged, e =>
        {
            if (e.Target == _owner) UnlockRecipes(_owner.ActiveTagIds);
        }, this);
        UnlockRecipes(_owner.ActiveTagIds);
    }

    /// <summary>根据标签解锁配方，返回新解锁列表。</summary>
    public List<Recipe> UnlockRecipes(IReadOnlySet<string> tagIds)
    {
        var newlyUnlocked = new List<Recipe>();
        if (RecipeRegistry.Instance == null) return newlyUnlocked;
        foreach (var recipe in RecipeRegistry.Instance.GetAll())
        {
            if (_knownRecipes.Contains(recipe)) continue;
            if (recipe.UnlockCondition.Evaluate(tagIds))
            {
                _knownRecipes.Add(recipe);
                newlyUnlocked.Add(recipe);
            }
        }
        return newlyUnlocked;
    }

    public IReadOnlyList<Recipe> GetKnownRecipes() => _knownRecipes.ToList();

    /// <summary>检查是否能制造（已解锁 + 材料充足）。</summary>
    public bool CanCraft(Recipe recipe, Item.Inventory inventory)
    {
        if (!_knownRecipes.Contains(recipe)) return false;
        return inventory.HasItems(recipe.Materials);
    }

    /// <summary>执行制造：扣材料 → 产出物品 → 发射 ITEM_ACQUIRED。</summary>
    public CraftResult Craft(Recipe recipe, Item.Inventory inventory)
    {
        if (!_knownRecipes.Contains(recipe))
            return new CraftResult(false, null, "配方未解锁");
        if (!inventory.HasItems(recipe.Materials))
            return new CraftResult(false, null, "材料不足");

        inventory.RemoveAll(recipe.Materials);
        inventory.Add(recipe.OutputItemId, recipe.OutputCount);
        _eventBus.Emit(EventBus.EventTypes.ItemAcquired, _owner, recipe.OutputItemId);
        return new CraftResult(true, recipe.OutputItemId, null);
    }
}

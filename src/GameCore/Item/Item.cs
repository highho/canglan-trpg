using System.Text.Json;

namespace GameCore.Item;

/// <summary>物品分类。</summary>
public enum ItemType { Material, Consumable, Equipment, Quest, Misc }

/// <summary>物品定义（items.json）。</summary>
public sealed record ItemDef(
    string Id,
    string Name,
    ItemType Type,
    int Value,            // 基础价值（金币）
    string Description,
    int MaxStack = 99,
    int Nutrition = 0);   // 营养值（食物恢复饱食度，生存系统用）

/// <summary>物品堆。</summary>
public sealed class ItemStack
{
    public ItemDef Def { get; }
    public int Count { get; set; }

    public ItemStack(ItemDef def, int count)
    {
        Def = def;
        Count = count;
    }

    public override string ToString() => $"{Def.Name}x{Count}";
}

/// <summary>物品注册表（从 items.json 加载）。</summary>
public sealed class ItemRegistry
{
    public static ItemRegistry Instance { get; private set; }

    private readonly Dictionary<string, ItemDef> _defsById = new();

    public void Load(string jsonPath) => LoadFromText(File.ReadAllText(jsonPath));

    public void LoadFromText(string json)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var node = prop.Value;
            var def = new ItemDef(
                prop.Name,
                node.TryGetProperty("name", out var n) ? n.GetString() : prop.Name,
                ParseType(node),
                node.TryGetProperty("value", out var v) ? v.GetInt32() : 0,
                node.TryGetProperty("description", out var d) ? d.GetString() : "",
                node.TryGetProperty("maxStack", out var m) ? m.GetInt32() : 99,
                node.TryGetProperty("nutrition", out var nu) ? nu.GetInt32() : 0);
            Register(def);
        }
        Instance = this;
    }

    /// <summary>未注册物品自动登记为 Misc（掉落物/任务物品容错）。</summary>
    public ItemDef GetOrRegister(string id)
    {
        if (_defsById.TryGetValue(id, out var def)) return def;
        def = new ItemDef(id, id, ItemType.Misc, 0, "");
        Register(def);
        return def;
    }

    public void Register(ItemDef def) => _defsById[def.Id] = def;

    public ItemDef Get(string id)
        => _defsById.TryGetValue(id, out var def) ? def : throw new ArgumentException($"未知物品: {id}");

    public bool TryGet(string id, out ItemDef def) => _defsById.TryGetValue(id, out def);

    public IEnumerable<ItemDef> GetAll() => _defsById.Values;

    private static ItemType ParseType(JsonElement node)
    {
        var raw = node.TryGetProperty("type", out var t) ? t.GetString() : "MISC";
        return raw.ToUpperInvariant() switch
        {
            "MATERIAL" => ItemType.Material,
            "CONSUMABLE" => ItemType.Consumable,
            "EQUIPMENT" => ItemType.Equipment,
            "QUEST" => ItemType.Quest,
            _ => ItemType.Misc
        };
    }
}

/// <summary>
/// Inventory — 背包容器。所有 Unit 都有背包：活着交易/打劫/装备，死了尸体=可搜刮容器。
/// </summary>
public sealed class Inventory
{
    private readonly Dictionary<string, ItemStack> _stacks = new();
    private readonly ItemRegistry _registry;

    public Inventory(ItemRegistry registry = null)
    {
        _registry = registry ?? ItemRegistry.Instance;
    }

    public IReadOnlyCollection<ItemStack> Stacks => _stacks.Values;

    public void Add(string itemId, int count = 1)
    {
        if (count <= 0) return;
        if (!_stacks.TryGetValue(itemId, out var stack))
        {
            var def = _registry != null ? _registry.GetOrRegister(itemId) : new ItemDef(itemId, itemId, ItemType.Misc, 0, "");
            _stacks[itemId] = stack = new ItemStack(def, 0);
        }
        stack.Count += count;
    }

    /// <summary>移除物品；数量不足返回 false 且不变更。</summary>
    public bool Remove(string itemId, int count = 1)
    {
        if (!_stacks.TryGetValue(itemId, out var stack) || stack.Count < count) return false;
        stack.Count -= count;
        if (stack.Count <= 0) _stacks.Remove(itemId);
        return true;
    }

    public int Count(string itemId)
        => _stacks.TryGetValue(itemId, out var stack) ? stack.Count : 0;

    public bool HasItem(string itemId) => Count(itemId) > 0;

    /// <summary>检查是否持有全部材料。</summary>
    public bool HasItems(IReadOnlyDictionary<string, int> materials)
        => materials.All(kv => Count(kv.Key) >= kv.Value);

    /// <summary>批量移除材料（调用前应先 HasItems 检查）。</summary>
    public void RemoveAll(IReadOnlyDictionary<string, int> materials)
    {
        foreach (var kv in materials) Remove(kv.Key, kv.Value);
    }

    public void Clear() => _stacks.Clear();

    /// <summary>存档序列化：{ itemId: count }。</summary>
    public Dictionary<string, int> ToSaveMap()
        => _stacks.ToDictionary(kv => kv.Key, kv => kv.Value.Count);

    /// <summary>读档恢复。</summary>
    public void LoadFrom(Dictionary<string, int> map)
    {
        Clear();
        if (map == null) return;
        foreach (var kv in map) Add(kv.Key, kv.Value);
    }
}

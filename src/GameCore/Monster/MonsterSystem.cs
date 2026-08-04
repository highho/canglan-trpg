using System.Text.Json;
using GameCore.Effect;
using GameCore.EventBus;
using GameCore.Unit;

namespace GameCore.Monster;

/// <summary>怪物战斗定位。</summary>
public enum CombatRole { Melee, Ranged, Support, Boss }

/// <summary>掉落条目。</summary>
public sealed record LootEntry(
    string ItemId,          // "哥布林之牙"
    float BaseChance,       // 0.0 ~ 1.0 基础掉落概率
    int MinQuantity,        // 最小数量
    int MaxQuantity,        // 最大数量
    string ConditionTag);   // 额外条件标签（如 [幸运]），null=无条件

/// <summary>
/// DropTable — 掉落系统。[幸运]标签 ×1.5 概率加成；条件标签不满足则不掉。
/// </summary>
public static class DropTable
{
    public static List<LootEntry> Roll(List<LootEntry> entries, Unit.Unit killer, Random rng)
    {
        var luckBonus = killer != null && killer.HasTag("幸运") ? 1.5f : 1.0f;
        var result = new List<LootEntry>();
        foreach (var entry in entries)
        {
            if (entry.ConditionTag != null && (killer == null || !killer.HasTag(entry.ConditionTag)))
                continue;
            if (rng.NextDouble() < entry.BaseChance * luckBonus)
                result.Add(entry);
        }
        return result;
    }

    /// <summary>掷骰结果 → 物品堆（数量在 min~max 间随机）。</summary>
    public static List<(string ItemId, int Count)> GenerateItems(List<LootEntry> rolled, Random rng)
    {
        var items = new List<(string, int)>();
        foreach (var e in rolled)
        {
            var qty = e.MinQuantity + rng.Next(e.MaxQuantity - e.MinQuantity + 1);
            items.Add((e.ItemId, qty));
        }
        return items;
    }
}

/// <summary>怪物模板（monsters.json）。</summary>
public sealed record MonsterTemplate(
    string Id,
    string Name,
    Dictionary<string, float> BaseStats,
    HashSet<string> RaceTagIds,
    HashSet<string> PersonalityTagIds,
    List<string> BehaviorPool,
    List<LootEntry> Drops,
    int ExpReward,
    CombatRole CombatRole,
    Dictionary<string, int> SpecialSkills);

/// <summary>怪物模板注册表（从 monsters.json 加载）。</summary>
public sealed class MonsterTemplateRegistry
{
    public static MonsterTemplateRegistry Instance { get; private set; }

    private readonly Dictionary<string, MonsterTemplate> _templates = new();

    public void Load(string jsonPath) => LoadFromText(File.ReadAllText(jsonPath));

    public void LoadFromText(string json)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
            Register(ParseTemplate(prop.Name, prop.Value));
        Instance = this;
    }

    public void Register(MonsterTemplate t) => _templates[t.Id] = t;

    public MonsterTemplate Get(string id)
        => _templates.TryGetValue(id, out var t) ? t : throw new ArgumentException($"未知怪物模板: {id}");

    public bool TryGet(string id, out MonsterTemplate t) => _templates.TryGetValue(id, out t);

    public IEnumerable<MonsterTemplate> GetAll() => _templates.Values;

    private static MonsterTemplate ParseTemplate(string id, JsonElement node)
    {
        var stats = new Dictionary<string, float>();
        if (node.TryGetProperty("baseStats", out var bs))
            foreach (var p in bs.EnumerateObject())
                stats[p.Name] = p.Value.GetSingle();

        var raceTags = ReadStringSet(node, "raceTagIds");
        var personalityTags = ReadStringSet(node, "personalityTagIds");
        var pool = node.TryGetProperty("behaviorPool", out var bp)
            ? bp.EnumerateArray().Select(e => e.GetString()).ToList()
            : new List<string> { "attack", "defend", "flee" };

        var drops = new List<LootEntry>();
        if (node.TryGetProperty("drops", out var dr))
        {
            foreach (var d in dr.EnumerateArray())
            {
                if (d.ValueKind == JsonValueKind.String)
                {
                    drops.Add(new LootEntry(d.GetString(), 0.5f, 1, 1, null));
                    continue;
                }
                drops.Add(new LootEntry(
                    d.GetProperty("itemId").GetString(),
                    d.TryGetProperty("chance", out var c) ? c.GetSingle() : 0.5f,
                    d.TryGetProperty("min", out var mn) ? mn.GetInt32() : 1,
                    d.TryGetProperty("max", out var mx) ? mx.GetInt32() : 1,
                    d.TryGetProperty("conditionTag", out var ct) ? ct.GetString() : null));
            }
        }

        var skills = new Dictionary<string, int>();
        if (node.TryGetProperty("specialSkills", out var ss))
            foreach (var p in ss.EnumerateObject())
                skills[p.Name] = p.Value.GetInt32();

        var roleRaw = node.TryGetProperty("combatRole", out var cr) ? cr.GetString() : "MELEE";
        var combatRole = roleRaw?.ToUpperInvariant() switch
        {
            "RANGED" => CombatRole.Ranged,
            "SUPPORT" => CombatRole.Support,
            "BOSS" => CombatRole.Boss,
            _ => CombatRole.Melee
        };

        return new MonsterTemplate(
            id,
            node.TryGetProperty("name", out var n) ? n.GetString() : id,
            stats, raceTags, personalityTags, pool, drops,
            node.TryGetProperty("expReward", out var exp) ? exp.GetInt32() : 0,
            combatRole, skills);
    }

    private static HashSet<string> ReadStringSet(JsonElement node, string prop)
    {
        var set = new HashSet<string>();
        if (node.TryGetProperty(prop, out var arr))
            foreach (var e in arr.EnumerateArray()) set.Add(e.GetString());
        return set;
    }
}

/// <summary>
/// MonsterFactory — 从模板创建怪物 Unit。
/// 怪物 = Unit 的战斗偏向：只有 combatPool、固定敌对、无社交能力。
/// 种族/人格标签进 TraitTagIds（怪物无进化图身份）；掉落表/经验值存 Metadata。
/// </summary>
public sealed class MonsterFactory
{
    private readonly Tag.TagFactory _tagFactory;
    private readonly EffectEngine _effectEngine;
    private readonly IEventBus _eventBus;
    private readonly Random _rng;

    public MonsterFactory(Tag.TagFactory tagFactory, EffectEngine effectEngine, IEventBus bus, Random rng = null)
    {
        _tagFactory = tagFactory;
        _effectEngine = effectEngine;
        _eventBus = bus;
        _rng = rng ?? new Random();
    }

    public Unit.Unit Create(MonsterTemplate template)
    {
        var monster = new Unit.Unit(template.Name, UnitRole.Monster, _tagFactory, _effectEngine, _eventBus);

        // 基础属性
        foreach (var (key, value) in template.BaseStats)
            monster.Stats.SetBase(key, value);
        monster.Stats.Hp = monster.MaxHp;

        // 标签：种族(IDENTITY) + 人格(PERSONALITY) → 特质集合
        foreach (var tagId in template.RaceTagIds) monster.TraitTagIds.Add(tagId);
        foreach (var tagId in template.PersonalityTagIds) monster.TraitTagIds.Add(tagId);
        monster.RecalculateTags();

        // 行为池（只有战斗池）
        monster.CombatPool = CreateBehaviorPool(template.Id, template.BehaviorPool);
        monster.ActivePool = monster.CombatPool;

        // 关系状态固定为敌对
        monster.RelationToPlayer = RelationState.Hostile;

        // 掉落表/经验值引用
        monster.Metadata["templateId"] = template.Id;
        monster.Metadata["drops"] = template.Drops;
        monster.Metadata["expReward"] = template.ExpReward;
        monster.Metadata["combatRole"] = template.CombatRole;
        monster.Metadata["specialSkills"] = template.SpecialSkills;

        return monster;
    }

    public Unit.Unit Create(string templateId)
        => Create(MonsterTemplateRegistry.Instance.Get(templateId));

    /// <summary>从全局预置选项构建战斗池（未知选项回退默认战斗池选项）。</summary>
    private BehaviorPool CreateBehaviorPool(string monsterId, List<string> optionIds)
    {
        var defaults = BehaviorPools.DefaultCombatPool();
        var pool = new BehaviorPool($"monster_{monsterId}", $"{monsterId}战斗池");
        foreach (var id in optionIds)
        {
            var option = defaults.Find(id);
            if (option != null) pool.Add(option);
        }
        if (pool.Options.Count == 0) pool.Add(defaults.Find("attack"));
        return pool;
    }

    /// <summary>击杀掉落：掷骰 → 物品入击杀者背包 → 发射 ITEM_ACQUIRED。</summary>
    public List<(string ItemId, int Count)> DropLoot(Unit.Unit monster, Unit.Unit killer)
    {
        var result = new List<(string, int)>();
        if (monster.Metadata.TryGetValue("drops", out var raw) && raw is List<LootEntry> drops)
        {
            var rolled = DropTable.Roll(drops, killer, _rng);
            result = DropTable.GenerateItems(rolled, _rng);
            foreach (var (itemId, count) in result)
            {
                killer?.Inventory?.Add(itemId, count);
                _eventBus.Emit(EventTypes.ItemAcquired, killer, itemId);
            }
        }
        return result;
    }
}

// ==================== 刷怪系统 ====================

/// <summary>刷怪条目。</summary>
public sealed record SpawnEntry(MonsterTemplate Template, int MinCount, int MaxCount, float Weight);

/// <summary>区域配置（生态+等级范围+刷怪条目）。</summary>
public sealed record AreaConfig(string Id, World.BiomeType Biome, int MinLevel, int MaxLevel, List<SpawnEntry> Entries);

/// <summary>
/// MonsterSpawner — 按区域配置刷怪：权重抽取条目 → 数量随机 → 区域内随机有效位置。
/// </summary>
public sealed class MonsterSpawner
{
    private readonly MonsterFactory _factory;
    private readonly World.WorldMap _worldMap;
    private readonly Random _rng;

    public MonsterSpawner(MonsterFactory factory, World.WorldMap worldMap, Random rng = null)
    {
        _factory = factory;
        _worldMap = worldMap;
        _rng = rng ?? new Random();
    }

    /// <summary>按区域配置刷怪，返回生成的怪物列表。</summary>
    public List<Unit.Unit> Spawn(AreaConfig config)
    {
        var spawned = new List<Unit.Unit>();
        foreach (var entry in config.Entries)
        {
            var count = entry.MinCount + _rng.Next(entry.MaxCount - entry.MinCount + 1);
            for (var i = 0; i < count; i++)
            {
                var pos = FindValidSpawnPos(config);
                if (pos == null) continue;
                var monster = _factory.Create(entry.Template);
                monster.WorldPos = pos;
                spawned.Add(monster);
            }
        }
        return spawned;
    }

    /// <summary>在区域内随机选有效位置（地图内即可；视野规避由上层战争迷雾决定）。</summary>
    private World.MapPos FindValidSpawnPos(AreaConfig config)
    {
        for (var attempt = 0; attempt < 10; attempt++)
        {
            var x = _rng.Next(_worldMap.Width);
            var y = _rng.Next(_worldMap.Height);
            if (_worldMap.CurrentBiome(new World.MapPos(x, y)) == config.Biome)
                return new World.MapPos(x, y);
        }
        return new World.MapPos(_rng.Next(_worldMap.Width), _rng.Next(_worldMap.Height));
    }
}

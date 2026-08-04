using System.Text.Json;
using GameCore.Effect;
using GameCore.EventBus;
using GameCore.Unit;

namespace GameCore.Creation;

/// <summary>特质定义（traits.json）。</summary>
public sealed record TraitDef(
    string Id,                  // "brave"
    string Name,                // "勇敢"
    HashSet<string> TagIds,     // ["勇敢"]
    int StartingGold,           // 初始金币加成
    string RaceRestriction,     // 种族限制，null=无限制
    string ClassRestriction,    // 职业限制，null=无限制
    string Description);

/// <summary>特质注册表（traits.json）。</summary>
public sealed class TraitRegistry
{
    public static TraitRegistry Instance { get; private set; }

    private readonly Dictionary<string, TraitDef> _traits = new();

    public void Load(string jsonPath) => LoadFromText(File.ReadAllText(jsonPath));

    public void LoadFromText(string json)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var n = prop.Value;
            var tagIds = new HashSet<string>();
            if (n.TryGetProperty("tagIds", out var ts))
                foreach (var t in ts.EnumerateArray()) tagIds.Add(t.GetString());
            Register(new TraitDef(
                prop.Name,
                n.TryGetProperty("name", out var nm) ? nm.GetString() : prop.Name,
                tagIds,
                n.TryGetProperty("startingGold", out var g) ? g.GetInt32() : 0,
                n.TryGetProperty("raceRestriction", out var rr) ? rr.GetString() : null,
                n.TryGetProperty("classRestriction", out var cr) ? cr.GetString() : null,
                n.TryGetProperty("description", out var d) ? d.GetString() : ""));
        }
        Instance = this;
    }

    public void Register(TraitDef t) => _traits[t.Id] = t;

    public TraitDef Get(string id)
        => _traits.TryGetValue(id, out var t) ? t : throw new ArgumentException($"未知特质: {id}");

    public bool TryGet(string id, out TraitDef t) => _traits.TryGetValue(id, out t);

    public IEnumerable<TraitDef> GetAll() => _traits.Values;
}

/// <summary>创建结果预览。</summary>
public sealed record CreationPreview(
    string Race,
    string ClassName,
    string Trait,
    IReadOnlySet<string> StartingTags,
    Dictionary<string, float> StartingStats,
    List<string> EvolutionPreview);

/// <summary>
/// CharacterCreation — 标签驱动的角色创建：三选（种族+职业+特质）→ recalculateTags → 一切自动确定。
/// 不需要属性点分配、不需要技能选择、不需要微调。
/// </summary>
public sealed class CharacterCreation
{
    private readonly Graph.GraphEngine<Graph.RaceData> _raceGraph;
    private readonly Graph.GraphEngine<Graph.ClassData> _classGraph;
    private readonly TraitRegistry _traitRegistry;
    private readonly Tag.TagFactory _tagFactory;
    private readonly EffectEngine _effectEngine;
    private readonly IEventBus _eventBus;
    private readonly HashSet<string> _startingRaceIds;

    public CharacterCreation(
        Graph.GraphEngine<Graph.RaceData> raceGraph,
        Graph.GraphEngine<Graph.ClassData> classGraph,
        TraitRegistry traitRegistry,
        Tag.TagFactory tagFactory,
        EffectEngine effectEngine,
        IEventBus eventBus,
        IEnumerable<string> startingRaceIds = null)
    {
        _raceGraph = raceGraph;
        _classGraph = classGraph;
        _traitRegistry = traitRegistry;
        _tagFactory = tagFactory;
        _effectEngine = effectEngine;
        _eventBus = eventBus;
        _startingRaceIds = startingRaceIds != null
            ? new HashSet<string>(startingRaceIds)
            : null;   // 缺省 = 仅根种族可选（进化形态需在冒险中经标签进化）
    }

    /// <summary>获取可选种族列表（缺省仅无入边的根种族）。</summary>
    public List<Graph.RaceNode> GetAvailableRaces()
        => _raceGraph.AllNodes.OfType<Graph.RaceNode>()
            .Where(n => _startingRaceIds?.Contains(n.Id) ?? n.IncomingEdges.Count == 0)
            .ToList();

    /// <summary>获取可选职业列表（根职业；双向边的回转入边不算来源，进阶职业需转职）。</summary>
    public List<Graph.ClassNode> GetAvailableClasses()
        => _classGraph.AllNodes.OfType<Graph.ClassNode>()
            .Where(n => n.IncomingEdges.All(e => e.Bidirectional))
            .ToList();

    /// <summary>获取某种族+职业下的可选特质（种族/职业限制过滤）。</summary>
    public List<TraitDef> GetAvailableTraits(Graph.RaceData race, Graph.ClassData cls)
        => _traitRegistry.GetAll()
            .Where(t => t.RaceRestriction == null || t.RaceRestriction == race.Name)
            .Where(t => t.ClassRestriction == null || t.ClassRestriction == cls.Name)
            .ToList();

    /// <summary>执行创建：种族+职业+特质 → 全量重建 → 初始装备与金币。</summary>
    public Unit.Unit Create(string raceId, string classId, string traitId, string playerName)
    {
        var raceNode = _raceGraph.GetNode(raceId) as Graph.RaceNode;
        var classNode = _classGraph.GetNode(classId) as Graph.ClassNode;
        var trait = _traitRegistry.Get(traitId);
        if (raceNode == null || classNode == null)
            throw new ArgumentException("无效的种族或职业选择");

        var player = new Unit.Unit(playerName, UnitRole.Player, _tagFactory, _effectEngine, _eventBus);

        // 1. 种族基础属性
        foreach (var (key, value) in raceNode.BaseStats)
            player.Stats.SetBase(key, value);

        // 2. 种族 + 职业
        player.ChangeRace(raceNode);
        player.ChangeClass(classNode);

        // 3. 特质标签
        foreach (var tagId in trait.TagIds) player.TraitTagIds.Add(tagId);

        // 4. 全量重建 → 标签集/属性/行为偏好全部确定
        player.RecalculateTags();
        player.Stats.Hp = player.MaxHp;

        // 5. 初始装备
        player.Inventory.Add("healing_potion", 3);
        player.Inventory.Add("travel_rations", 5);

        // 6. 初始金币 = 特质加成 + 基础100
        player.Gold = trait.StartingGold + 100;

        return player;
    }

    /// <summary>创建结果预览（标签集/属性/进化方向）。</summary>
    public CreationPreview Preview(Unit.Unit player, string traitName)
    {
        var stats = new Dictionary<string, float>();
        foreach (var key in new[] { "HP", "ATK", "DEF", "SPD", "CRIT" })
            stats[key] = player.GetStat(key);

        var evolution = new List<string>();
        evolution.AddRange(_raceGraph.GetAvailableNodes(player.ActiveTagIds)
            .Where(n => n.Id != player.CurrentRace?.Id).Select(n => n.Data is Graph.RaceData rd ? rd.Name : n.Id));
        evolution.AddRange(_classGraph.GetAvailableNodes(player.ActiveTagIds)
            .Where(n => n.Id != player.CurrentClass?.Id).Select(n => n.Data is Graph.ClassData cd ? cd.Name : n.Id));

        return new CreationPreview(
            player.CurrentRace?.Name ?? "",
            player.CurrentClass?.Name ?? "",
            traitName,
            player.ActiveTagIds,
            stats,
            evolution);
    }
}

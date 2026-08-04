using System.Text.Json;
using GameCore.Graph;
using GameCore.Tag;

namespace GameCore.Skill;

/// <summary>技能节点数据（技能也用图结构）。</summary>
public sealed record SkillData(Skill Skill);

/// <summary>技能树节点。</summary>
public sealed class SkillNode : GraphNode<SkillData>
{
    public SkillNode(string id, SkillData data) : base(id, data) { }
}

/// <summary>
/// SkillTree — 职业技能树。根节点（无入边）职业转职时直接获得；
/// 其余节点由入边标签条件解锁（CheckUnlocks 在标签变化后调用）。
/// </summary>
public sealed class SkillTree
{
    public string Id { get; }
    private readonly GraphEngine<SkillData> _graph;
    private readonly HashSet<string> _unlockedSkillIds = new();

    public SkillTree(string id, GraphEngine<SkillData> graph)
    {
        Id = id;
        _graph = graph;
    }

    public GraphEngine<SkillData> Graph => _graph;
    public IReadOnlySet<string> UnlockedSkillIds => _unlockedSkillIds;

    /// <summary>转职时解锁全部根节点（无入边的技能）。</summary>
    public List<Skill> UnlockRoots()
    {
        var newly = new List<Skill>();
        foreach (var node in _graph.AllNodes)
        {
            if (node.IncomingEdges.Count > 0) continue;
            if (_unlockedSkillIds.Add(node.Id))
                newly.Add(node.Data.Skill);
        }
        return newly;
    }

    /// <summary>检查是否有新技能可解锁（任一入边条件满足）。</summary>
    public List<Skill> CheckUnlocks(IReadOnlySet<string> tagIds)
    {
        var newlyUnlocked = new List<Skill>();
        foreach (var node in _graph.AllNodes)
        {
            if (_unlockedSkillIds.Contains(node.Id)) continue;
            if (node.IncomingEdges.Count == 0) continue;
            if (node.IncomingEdges.Any(e => e.Condition == null || e.Condition.Evaluate(tagIds)))
            {
                _unlockedSkillIds.Add(node.Id);
                newlyUnlocked.Add(node.Data.Skill);
            }
        }
        return newlyUnlocked;
    }

    public List<Skill> GetUnlockedSkills()
        => _unlockedSkillIds
            .Select(id => _graph.GetNode(id))
            .Where(n => n != null)
            .Select(n => n.Data.Skill)
            .ToList();

    /// <summary>存档恢复：直接设置已解锁集合。</summary>
    public void RestoreUnlocks(IEnumerable<string> skillIds)
    {
        _unlockedSkillIds.Clear();
        foreach (var id in skillIds) _unlockedSkillIds.Add(id);
    }
}

/// <summary>技能树注册表（skillTrees.json）：{ treeId: { nodes:[skillId], edges:[{from,to,condition}] } }。</summary>
public sealed class SkillTreeRegistry
{
    public static SkillTreeRegistry Instance { get; private set; }

    private readonly Dictionary<string, SkillTree> _treesById = new();
    private readonly TagConditionParser _conditionParser;
    private readonly SkillRegistry _skillRegistry;

    public SkillTreeRegistry(TagConditionParser conditionParser, SkillRegistry skillRegistry)
    {
        _conditionParser = conditionParser;
        _skillRegistry = skillRegistry;
    }

    public void Load(string jsonPath) => LoadFromText(File.ReadAllText(jsonPath));

    public void LoadFromText(string json)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var treeProp in doc.RootElement.EnumerateObject())
        {
            var graph = new GraphEngine<SkillData>(_conditionParser);
            var tree = treeProp.Value;

            if (tree.TryGetProperty("nodes", out var nodes))
            {
                foreach (var n in nodes.EnumerateArray())
                {
                    var skillId = n.GetString();
                    graph.AddNode(new SkillNode(skillId, new SkillData(_skillRegistry.Get(skillId))));
                }
            }
            if (tree.TryGetProperty("edges", out var edges))
            {
                foreach (var e in edges.EnumerateArray())
                {
                    ITagCondition condition = e.TryGetProperty("condition", out var c) && c.ValueKind == JsonValueKind.String
                        ? _conditionParser.Parse(c.GetString())
                        : null;
                    graph.Connect(e.GetProperty("from").GetString(), e.GetProperty("to").GetString(), condition);
                }
            }
            Register(new SkillTree(treeProp.Name, graph));
        }
        Instance = this;
    }

    public void Register(SkillTree tree) => _treesById[tree.Id] = tree;

    public SkillTree Get(string id)
        => _treesById.TryGetValue(id, out var t) ? t : throw new ArgumentException($"未知技能树: {id}");

    public bool TryGet(string id, out SkillTree tree) => _treesById.TryGetValue(id, out tree);
}

/// <summary>
/// CooldownManager — 冷却集中管理。回合结束时所有技能冷却 -1。
/// </summary>
public sealed class CooldownManager
{
    private readonly Unit.Unit _owner;
    private readonly Dictionary<string, Skill> _skills = new();

    public CooldownManager(Unit.Unit owner, EventBus.IEventBus bus)
    {
        _owner = owner;
        bus.SubscribeWithOwner(EventBus.EventTypes.TurnEnd, _ =>
        {
            foreach (var s in _skills.Values) s.TickCooldown();
        }, this);
    }

    public void AddSkill(Skill s) => _skills[s.Id] = s;

    public void RemoveSkill(string skillId) => _skills.Remove(skillId);

    public Skill GetSkill(string skillId) => _skills.TryGetValue(skillId, out var s) ? s : null;

    public bool CanUse(string skillId)
    {
        var s = GetSkill(skillId);
        return s != null && s.IsReady();
    }

    public List<Skill> GetUsableSkills() => _skills.Values.Where(s => s.IsReady()).ToList();

    public IReadOnlyCollection<Skill> GetAll() => _skills.Values;

    /// <summary>存档用：skillId → 剩余冷却。</summary>
    public Dictionary<string, int> ToSaveMap() => _skills.ToDictionary(kv => kv.Key, kv => kv.Value.CurrentCooldown);
}

/// <summary>
/// SkillSlot — 技能槽位（最多6个）。被动技能无需装备即生效。
/// </summary>
public sealed class SkillSlot
{
    public const int MaxSlots = 6;

    private readonly List<Skill> _slots = new();

    public bool Equip(Skill skill)
    {
        if (_slots.Count >= MaxSlots) return false;
        if (_slots.Any(s => s.Id == skill.Id)) return false;
        _slots.Add(skill);
        return true;
    }

    public bool Unequip(string skillId)
        => _slots.RemoveAll(s => s.Id == skillId) > 0;

    public List<Skill> GetEquipped() => _slots.ToList();

    /// <summary>被动技能（无需装备即生效）。</summary>
    public static List<Skill> GetPassives(IEnumerable<Skill> allUnlocked)
        => allUnlocked.Where(s => s.Type == SkillType.Passive).ToList();
}

using System.Text.Json;
using GameCore.Effect;
using GameCore.EventBus;
using GameCore.Unit;

namespace GameCore.Npc;

/// <summary>关系层级（好感度映射）。</summary>
public enum RelationLevel { Ally, Friendly, Neutral, Hostile, Enemy }

/// <summary>
/// RelationshipMap — 好感度矩阵（NPC对各单位）。
/// 静态成员支持队友间双向好感度调整。
/// </summary>
public sealed class RelationshipMap
{
    private readonly Dictionary<string, int> _affinities = new();   // unitId → 好感度

    public int Get(string unitId) => _affinities.TryGetValue(unitId, out var v) ? v : 0;

    public void Adjust(string unitId, int delta)
        => _affinities[unitId] = Math.Clamp(Get(unitId) + delta, -100, 100);

    /// <summary>好感度 → 关系层级：≥80盟友 / ≥40友好 / ≥0中立 / ≥-40敌视 / &lt;-40死敌。</summary>
    public RelationLevel GetLevel(string unitId) => Get(unitId) switch
    {
        >= 80 => RelationLevel.Ally,
        >= 40 => RelationLevel.Friendly,
        >= 0 => RelationLevel.Neutral,
        >= -40 => RelationLevel.Hostile,
        _ => RelationLevel.Enemy
    };

    /// <summary>两个Unit之间的好感度（队友好感度矩阵）。</summary>
    public static int Between(Unit.Unit a, Unit.Unit b) => a.GetAllyAffinity(b);

    /// <summary>调整队友间好感度（双向对称）。</summary>
    public static void AdjustAllyAffinity(Unit.Unit a, Unit.Unit b, int delta)
    {
        a.AddAllyAffinity(b, delta);
        b.AddAllyAffinity(a, delta);
    }
}

/// <summary>NPC 定义（npcs.json）。</summary>
public sealed record NpcDef(
    string Id,
    string Name,
    HashSet<string> IdentityTags,
    HashSet<string> PersonalityTags,
    Dictionary<string, float> BaseStats,
    RelationState InitialRelation,
    DialogueTree DialogueTree);

/// <summary>NPC 注册表（从 npcs.json 加载）。</summary>
public sealed class NpcRegistry
{
    public static NpcRegistry Instance { get; private set; }

    private readonly Dictionary<string, NpcDef> _defs = new();

    public void Load(string jsonPath) => LoadFromText(File.ReadAllText(jsonPath));

    public void LoadFromText(string json)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var n = prop.Value;
            var identity = ReadStringSet(n, "identityTags");
            var personality = ReadStringSet(n, "personalityTags");
            var stats = new Dictionary<string, float>();
            if (n.TryGetProperty("baseStats", out var bs))
                foreach (var p in bs.EnumerateObject())
                    stats[p.Name] = p.Value.GetSingle();

            var relationRaw = n.TryGetProperty("relation", out var r) ? r.GetString() : "NEUTRAL";
            var relation = relationRaw?.ToUpperInvariant() switch
            {
                "HOSTILE" => RelationState.Hostile,
                "FRIENDLY" => RelationState.Friendly,
                "ALLY" => RelationState.Ally,
                _ => RelationState.Neutral
            };

            DialogueTree tree = null;
            if (n.TryGetProperty("dialogueTree", out var dt))
                tree = DialogueTreeLoader.Load(dt);

            Register(new NpcDef(prop.Name,
                n.TryGetProperty("name", out var nm) ? nm.GetString() : prop.Name,
                identity, personality, stats, relation, tree));
        }
        Instance = this;
    }

    public void Register(NpcDef def) => _defs[def.Id] = def;

    public NpcDef Get(string id)
        => _defs.TryGetValue(id, out var d) ? d : throw new ArgumentException($"未知NPC: {id}");

    public bool TryGet(string id, out NpcDef def) => _defs.TryGetValue(id, out def);

    public IEnumerable<NpcDef> GetAll() => _defs.Values;

    private static HashSet<string> ReadStringSet(JsonElement node, string prop)
    {
        var set = new HashSet<string>();
        if (node.TryGetProperty(prop, out var arr))
            foreach (var e in arr.EnumerateArray()) set.Add(e.GetString());
        return set;
    }
}

/// <summary>
/// NPCFactory — NPC 工厂。NPC = Unit 的社交偏向：
/// socialPool 激活 + 可变关系状态 + 对话树 + 切磋/打劫/袭杀战斗模式切换。
/// </summary>
public sealed class NPCFactory
{
    private readonly Tag.TagFactory _tagFactory;
    private readonly EffectEngine _effectEngine;
    private readonly IEventBus _eventBus;

    public NPCFactory(Tag.TagFactory tagFactory, EffectEngine effectEngine, IEventBus bus)
    {
        _tagFactory = tagFactory;
        _effectEngine = effectEngine;
        _eventBus = bus;
    }

    public Unit.Unit Create(NpcDef def)
    {
        var npc = new Unit.Unit(def.Name, UnitRole.Npc, _tagFactory, _effectEngine, _eventBus);

        foreach (var (key, value) in def.BaseStats)
            npc.Stats.SetBase(key, value);
        npc.Stats.Hp = npc.MaxHp;

        // 身份(IDENTITY) + 人格(PERSONALITY) 标签 → 特质集合
        foreach (var tagId in def.IdentityTags) npc.TraitTagIds.Add(tagId);
        foreach (var tagId in def.PersonalityTags) npc.TraitTagIds.Add(tagId);
        npc.RecalculateTags();

        // 行为池：社交池激活，战斗池备用
        npc.SocialPool = BehaviorPools.DefaultSocialPool();
        npc.CombatPool = BehaviorPools.DefaultCombatPool();
        npc.ActivePool = npc.SocialPool;

        npc.RelationToPlayer = def.InitialRelation;
        npc.Metadata["npcId"] = def.Id;
        npc.Metadata["dialogueTree"] = def.DialogueTree;
        npc.Metadata["relationships"] = new RelationshipMap();

        return npc;
    }

    public Unit.Unit Create(string npcId) => Create(NpcRegistry.Instance.Get(npcId));

    /// <summary>获取NPC对话树（可能为 null）。</summary>
    public static DialogueTree GetDialogueTree(Unit.Unit npc)
        => npc.Metadata.TryGetValue("dialogueTree", out var raw) ? raw as DialogueTree : null;

    /// <summary>获取NPC关系矩阵。</summary>
    public static RelationshipMap GetRelationships(Unit.Unit npc)
        => npc.Metadata.TryGetValue("relationships", out var raw) ? raw as RelationshipMap : null;

    /// <summary>
    /// 选择对话入口：战斗监听触发用 trigger 节点，否则根节点。
    /// 返回 (节点, 上下文)，条件按玩家标签评估。
    /// </summary>
    public static (DialogueNode Node, Tag.EvalContext Ctx) SelectDialogue(Unit.Unit player, Unit.Unit npc, string trigger = null)
    {
        var tree = GetDialogueTree(npc);
        if (tree == null) return (null, null);
        var ctx = new Tag.EvalContext(player.ActiveTagIds, new Dictionary<string, object>(), player, npc);
        var node = trigger != null ? tree.SelectTrigger(trigger, player) : tree.GetRoot();
        return (node, ctx);
    }
}

/// <summary>
/// 打劫响应池 — 被打劫时 NPC 的行为决策选项（FIGHT_BACK / SURRENDER / FLEE）。
/// </summary>
public static class RobResponse
{
    public static BehaviorPool Pool()
    {
        var pool = new BehaviorPool("rob_response", "打劫响应池");
        pool.Add(new BehaviorOption("fight_back", "反击", 40,
            new Dictionary<string, Dictionary<string, int>>
            {
                ["PERSONALITY"] = new() { ["勇敢"] = 30, ["懦弱"] = -20 },
                ["EMOTION"] = new() { ["愤怒"] = 20 }
            }));
        pool.Add(new BehaviorOption("surrender", "投降", 30,
            new Dictionary<string, Dictionary<string, int>>
            {
                ["PERSONALITY"] = new() { ["懦弱"] = 40, ["勇敢"] = -30 },
                ["EMOTION"] = new() { ["恐惧"] = 30 }
            }));
        pool.Add(new BehaviorOption("flee", "逃跑", 20,
            new Dictionary<string, Dictionary<string, int>>
            {
                ["PERSONALITY"] = new() { ["狡猾"] = 20 },
                ["EMOTION"] = new() { ["恐惧"] = 20 }
            }));
        return pool;
    }
}

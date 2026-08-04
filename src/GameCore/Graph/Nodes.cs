using GameCore.Tag;

namespace GameCore.Graph;

// ==================== 种族 ====================

/// <summary>种族数据。</summary>
public sealed record RaceData(
    string Name,                              // "天使" / "堕天使"
    IReadOnlySet<string> TagIds,              // 进化获得: ["神圣","光明"]
    IReadOnlyDictionary<string, float> BaseStats,   // { HP: 80, ATK: 12, DEF: 8 }
    IReadOnlySet<string> ConflictTags);       // 进化时需清除的标签: ["神圣","光明"]

/// <summary>种族节点（种族进化图 = DAG，不可逆）。</summary>
public sealed class RaceNode : GraphNode<RaceData>
{
    public RaceNode(string id, RaceData data) : base(id, data) { }

    public string Name => Data.Name;
    public IReadOnlySet<string> TagIds => Data.TagIds;
    public IReadOnlyDictionary<string, float> BaseStats => Data.BaseStats;
    public IReadOnlySet<string> ConflictTags => Data.ConflictTags;
}

// ==================== 职业 ====================

/// <summary>职业数据。SkillTreeRoot 为技能树入口ID（→ 技能系统）。</summary>
public sealed record ClassData(
    string Name,                              // "魔剑士"
    IReadOnlySet<string> TagIds,              // 转职获得: ["近战","魔能","黑暗"]
    string SkillTreeRoot,                     // 技能树入口
    IReadOnlyDictionary<string, float> StatGrowth); // 每级属性成长: { ATK: 2.5, DEF: 1.0 }

/// <summary>职业节点（职业转职图 = DAG，可降级转回）。</summary>
public sealed class ClassNode : GraphNode<ClassData>
{
    public ClassNode(string id, ClassData data) : base(id, data) { }

    public string Name => Data.Name;
    public IReadOnlySet<string> TagIds => Data.TagIds;
    public string SkillTreeRoot => Data.SkillTreeRoot;
    public IReadOnlyDictionary<string, float> StatGrowth => Data.StatGrowth;
}

// ==================== 任务 ====================

/// <summary>任务数据。</summary>
public sealed record QuestData(
    string Name,                              // "S级:屠龙"
    string Description,
    ITagCondition AcceptCondition,            // 接受条件（与边条件独立，允许更严格）
    int MinLevel,
    int Cooldown,                             // 可重复任务冷却回合数，0=一次性
    IReadOnlySet<string> RewardTagIds,        // 完成后获得的 QUEST_MARK 标签
    IReadOnlyDictionary<string, int> Rewards);      // { gold: 5000, "屠龙者徽章": 1 }

/// <summary>任务节点（任务图 = 有向图允许环，支持多前置/分支汇合/可重复）。</summary>
public sealed class QuestNode : GraphNode<QuestData>
{
    public QuestNode(string id, QuestData data) : base(id, data) { }

    public string Name => Data.Name;

    public bool IsRepeatable() => Data.Cooldown > 0;

    /// <summary>接受检查：等级达标 + 任一入边条件满足（无入边则用 AcceptCondition）。</summary>
    public bool CanAccept(IReadOnlySet<string> tagIds, int playerLevel)
    {
        if (Data.MinLevel > playerLevel) return false;
        if (IncomingEdges.Count == 0)
            return Data.AcceptCondition?.Evaluate(tagIds) ?? true;
        return IncomingEdges.Any(e => e.Condition == null || e.Condition.Evaluate(tagIds));
    }
}

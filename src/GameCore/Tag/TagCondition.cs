namespace GameCore.Tag;

/// <summary>统一条件评估接口：驱动种族进化/职业转职/任务触发/NPC对话分支/技能解锁五大方向。</summary>
public interface ITagCondition
{
    bool Evaluate(IReadOnlySet<string> tagIds);
}

/// <summary>拥有某标签。</summary>
public sealed record HasTag(string TagId) : ITagCondition
{
    public bool Evaluate(IReadOnlySet<string> tagIds) => tagIds.Contains(TagId);
    public override string ToString() => $"HasTag({TagId})";
}

/// <summary>拥有全部指定标签。</summary>
public sealed record HasAllTags(IReadOnlySet<string> Required) : ITagCondition
{
    public bool Evaluate(IReadOnlySet<string> tagIds) => Required.All(tagIds.Contains);
    public override string ToString() => $"HasAllTags([{string.Join(",", Required)}])";
}

/// <summary>拥有任一指定标签。</summary>
public sealed record HasAnyTag(IReadOnlySet<string> Candidates) : ITagCondition
{
    public bool Evaluate(IReadOnlySet<string> tagIds) => Candidates.Any(tagIds.Contains);
    public override string ToString() => $"HasAnyTag([{string.Join(",", Candidates)}])";
}

/// <summary>
/// 持有指定标签（id 基础名）且 tier 达到 minTier。
/// 设计文档示例 HasTag(火焰Lv3) 解析为对「火焰」标签的层级要求。
/// </summary>
public sealed record TagTierAtLeast(string TagId, int MinTier) : ITagCondition
{
    public bool Evaluate(IReadOnlySet<string> tagIds)
    {
        if (!tagIds.Contains(TagId)) return false;
        var registry = TagRegistry.Instance;
        if (registry == null) return true;
        // [适性]的涌现效果：所有层级条件要求统一减免
        var required = MinTier - registry.TierReduction;
        var def = registry.Get(TagId);
        return def != null && def.Tier >= required;
    }
    public override string ToString() => $"TierAtLeast({TagId},{MinTier})";
}

/// <summary>条件组合：AND。</summary>
public sealed record AndCondition(IReadOnlyList<ITagCondition> Conditions) : ITagCondition
{
    public bool Evaluate(IReadOnlySet<string> tagIds) => Conditions.All(c => c.Evaluate(tagIds));
}

/// <summary>条件组合：OR。</summary>
public sealed record OrCondition(IReadOnlyList<ITagCondition> Conditions) : ITagCondition
{
    public bool Evaluate(IReadOnlySet<string> tagIds) => Conditions.Any(c => c.Evaluate(tagIds));
}

/// <summary>条件组合：NOT。</summary>
public sealed record NotCondition(ITagCondition Condition) : ITagCondition
{
    public bool Evaluate(IReadOnlySet<string> tagIds) => !Condition.Evaluate(tagIds);
}

/// <summary>恒真条件（对话树默认分支用）。</summary>
public sealed record AlwaysTrue() : ITagCondition
{
    public bool Evaluate(IReadOnlySet<string> tagIds) => true;
}

/// <summary>条件评估上下文：当条件需要更多运行时信息时使用（如 NPC 对话匹配双方标签）。</summary>
public sealed record EvalContext(
    IReadOnlySet<string> UnitTagIds,
    Dictionary<string, object> Extra,
    Unit.Unit Player = null,
    Unit.Unit Npc = null)
{
    public T GetExtra<T>(string key) where T : class
        => Extra != null && Extra.TryGetValue(key, out var v) ? v as T : null;

    public int GetExtraInt(string key, int fallback = 0)
        => Extra != null && Extra.TryGetValue(key, out var v) ? Convert.ToInt32(v) : fallback;
}

/// <summary>扩展接口 — 允许传入上下文。</summary>
public interface IContextualCondition
{
    bool Evaluate(EvalContext ctx);
}

/// <summary>ITagCondition → IContextualCondition 适配器。</summary>
public sealed class TagConditionAdapter : IContextualCondition
{
    private readonly ITagCondition _condition;
    public TagConditionAdapter(ITagCondition c) => _condition = c;
    public bool Evaluate(EvalContext ctx) => _condition.Evaluate(ctx.UnitTagIds);
}

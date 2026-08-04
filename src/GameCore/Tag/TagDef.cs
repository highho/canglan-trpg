using GameCore.Effect;

namespace GameCore.Tag;

/// <summary>标签六大分类。</summary>
public enum TagCategory
{
    /// <summary>元素：属性加成、克制关系。</summary>
    Element,
    /// <summary>身份：可用行为集合、社会角色。</summary>
    Identity,
    /// <summary>人格：行为偏好权重。</summary>
    Personality,
    /// <summary>情感：临时行为修正（高频变化）。</summary>
    Emotion,
    /// <summary>任务标记：任务链分支条件（不可逆）。</summary>
    QuestMark,
    /// <summary>技能：战斗行为。</summary>
    Skill
}

/// <summary>标签来源白名单：回答「谁能携带这个标签」。</summary>
public enum TagSource
{
    Race, Class, Quest, Trait, Equip, Buff
}

/// <summary>
/// 设计契约层标签定义。effectsJson 在加载时由 EffectParser 解析为强类型效果列表；
/// behaviorWeights 结构: pool(combat/social) → {optionId → weight}。
/// </summary>
public sealed record TagDef(
    string Id,
    string Name,
    string Description,
    TagCategory Category,
    int Tier,
    IReadOnlySet<TagSource> AllowedSources,
    IReadOnlyList<IEffectDef> Effects,
    IReadOnlyDictionary<string, Dictionary<string, int>> BehaviorWeights)
{
    public bool IsAllowedFrom(TagSource source) => AllowedSources.Contains(source);
}

/// <summary>运行时标签实例：由 TagFactory 从 TagDef 创建。</summary>
public sealed class Tag
{
    public string Id { get; }
    public string Name { get; }
    public string Description { get; }
    public TagCategory Category { get; }
    public int Tier { get; }
    public IReadOnlySet<TagSource> AllowedSources { get; }
    public IReadOnlyList<IEffectDef> Effects { get; }
    /// <summary>结构: pool(COMBAT/SOCIAL) → {optionId → weight}。</summary>
    public IReadOnlyDictionary<string, Dictionary<string, int>> BehaviorWeights { get; }

    public Tag(TagDef def)
    {
        Id = def.Id;
        Name = def.Name;
        Description = def.Description;
        Category = def.Category;
        Tier = def.Tier;
        AllowedSources = def.AllowedSources;
        Effects = def.Effects;
        BehaviorWeights = def.BehaviorWeights ?? new Dictionary<string, Dictionary<string, int>>();
    }

    public bool IsAllowedFrom(TagSource source) => AllowedSources.Contains(source);

    public override string ToString() => $"{Name}({Id})";
}

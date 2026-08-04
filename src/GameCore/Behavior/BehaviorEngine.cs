namespace GameCore.Behavior;

/// <summary>
/// BehaviorEngine — 权重法决策引擎。同一引擎驱动怪物AI和NPC交互。
/// 最终权重 = max(0, 基础权重 + Σ身份修正 + Σ人格修正 + Σ情感修正)。
/// </summary>
public sealed class BehaviorEngine
{
    private readonly Random _rng;

    public BehaviorEngine(Random rng = null)
    {
        _rng = rng ?? new Random();
    }

    /// <summary>决策：返回权重最高的选项（全部为0时返回第一项）。</summary>
    public Unit.BehaviorOption Decide(IReadOnlyList<Tag.Tag> tags, IReadOnlyList<Unit.BehaviorOption> options)
    {
        if (options == null || options.Count == 0) return null;
        Unit.BehaviorOption best = null;
        var bestWeight = int.MinValue;
        foreach (var opt in options)
        {
            var weight = ComputeWeight(opt, tags);
            if (weight > bestWeight)
            {
                bestWeight = weight;
                best = opt;
            }
        }
        return best ?? options[0];
    }

    /// <summary>决策（带候选权重输出，调试/UI展示用）。</summary>
    public (Unit.BehaviorOption Option, int Weight) DecideWithWeight(
        IReadOnlyList<Tag.Tag> tags, IReadOnlyList<Unit.BehaviorOption> options)
    {
        if (options == null || options.Count == 0) return (null, 0);
        Unit.BehaviorOption best = options[0];
        var bestWeight = int.MinValue;
        foreach (var opt in options)
        {
            var weight = ComputeWeight(opt, tags);
            if (weight > bestWeight)
            {
                bestWeight = weight;
                best = opt;
            }
        }
        return (best, Math.Max(0, bestWeight));
    }

    /// <summary>计算单个选项的最终权重 = max(0, 基础权重 + 标签修正之和)。</summary>
    public int ComputeWeight(Unit.BehaviorOption option, IReadOnlyList<Tag.Tag> tags)
    {
        var weight = option.BaseWeight;
        foreach (var tag in tags)
        {
            var categoryKey = CategoryKey(tag.Category);
            if (option.TagWeights != null
                && option.TagWeights.TryGetValue(categoryKey, out var catWeights)
                && catWeights.TryGetValue(tag.Id, out var mod))
            {
                weight += mod;
            }
        }
        return Math.Max(0, weight);
    }

    /// <summary>按权重随机抽取（权重越高概率越大；用于背刺判定等概率场景）。</summary>
    public Unit.BehaviorOption RollWeighted(IReadOnlyList<Tag.Tag> tags, IReadOnlyList<Unit.BehaviorOption> options)
    {
        if (options == null || options.Count == 0) return null;
        var weights = options.Select(o => ComputeWeight(o, tags)).ToList();
        var total = weights.Sum();
        if (total <= 0) return options[_rng.Next(options.Count)];
        var roll = _rng.Next(total);
        for (int i = 0; i < options.Count; i++)
        {
            roll -= weights[i];
            if (roll < 0) return options[i];
        }
        return options[^1];
    }

    /// <summary>TagCategory → tagWeights 字典键（IDENTITY/PERSONALITY/EMOTION/...）。</summary>
    public static string CategoryKey(Tag.TagCategory category) => category switch
    {
        Tag.TagCategory.Element => "ELEMENT",
        Tag.TagCategory.Identity => "IDENTITY",
        Tag.TagCategory.Personality => "PERSONALITY",
        Tag.TagCategory.Emotion => "EMOTION",
        Tag.TagCategory.QuestMark => "QUEST_MARK",
        Tag.TagCategory.Skill => "SKILL",
        _ => category.ToString().ToUpperInvariant()
    };
}

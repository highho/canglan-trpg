using System.Text.RegularExpressions;

namespace GameCore.Tag;

/// <summary>
/// TagConditionParser — 条件表达式字符串 → 条件树。
/// 支持: HasTag(火焰Lv3) / HasAllTags([神圣,光明,Lv5]) / HasAnyTag([匕首,毒]) /
///       TierAtLeast(SKILL,5) / NOT x / x AND y / x OR y。
/// 列表内的 "LvN" 项会附加到前一个标签项上，形成层级条件。
/// </summary>
public sealed class TagConditionParser
{
    private static readonly Regex LvSuffix = new(@"^(.*?)Lv(\d+)$", RegexOptions.Compiled);

    /// <summary>入口：解析条件表达式字符串。</summary>
    public ITagCondition Parse(string expr)
    {
        expr = expr.Trim();
        if (expr.StartsWith("HasAllTags(")) return ParseHasAllTags(expr);
        if (expr.StartsWith("HasAnyTag(")) return ParseHasAnyTag(expr);
        if (expr.StartsWith("HasTag(")) return ParseHasTag(expr);
        if (expr.StartsWith("TierAtLeast(")) return ParseTierAtLeast(expr);
        if (expr.StartsWith("NOT ")) return new NotCondition(Parse(expr[4..]));

        var andIdx = IndexOfOutsideParens(expr, " AND ");
        if (andIdx >= 0)
        {
            var parts = SplitOutsideParens(expr, " AND ");
            return new AndCondition(parts.Select(Parse).ToList());
        }
        var orIdx = IndexOfOutsideParens(expr, " OR ");
        if (orIdx >= 0)
        {
            var parts = SplitOutsideParens(expr, " OR ");
            return new OrCondition(parts.Select(Parse).ToList());
        }
        throw new ArgumentException($"无法解析条件: {expr}");
    }

    /// <summary>"HasTag(暗杀)" → HasTag；"HasTag(火焰Lv3)" → TagTierAtLeast(火焰,3)。</summary>
    private ITagCondition ParseHasTag(string expr)
    {
        var inner = Inner(expr, "HasTag(").Trim();
        var m = LvSuffix.Match(inner);
        if (m.Success && m.Groups[1].Value.Length > 0)
            return new TagTierAtLeast(m.Groups[1].Value, int.Parse(m.Groups[2].Value));
        return new HasTag(inner);
    }

    private ITagCondition ParseHasAllTags(string expr)
        => ParseHasAllTagsFull(expr);

    private ITagCondition ParseHasAnyTag(string expr)
        => new HasAnyTag(ParseTagList(Inner(expr, "HasAnyTag(")));

    /// <summary>
    /// "[神圣,光明,Lv5]" → {HasTag(神圣), TagTierAtLeast(光明,5)}。
    /// 形如 "火焰Lv3" 的内联层级写法同样支持。
    /// </summary>
    private static IReadOnlySet<ITagCondition> ParseTagListAsConditions(string inner)
    {
        inner = inner.Trim().Trim('[', ']').Trim();
        var conditions = new List<ITagCondition>();
        foreach (var raw in inner.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var m = LvSuffix.Match(raw);
            if (m.Success && m.Groups[1].Value.Length > 0)
                conditions.Add(new TagTierAtLeast(m.Groups[1].Value, int.Parse(m.Groups[2].Value)));
            else if (raw.StartsWith("Lv", StringComparison.OrdinalIgnoreCase) && conditions.Count > 0)
            {
                // 裸 LvN → 提升前一项为层级条件
                if (conditions[^1] is HasTag ht)
                {
                    conditions[^1] = new TagTierAtLeast(ht.TagId, int.Parse(raw[2..]));
                }
            }
            else
                conditions.Add(new HasTag(raw));
        }
        return conditions.Count == 1 && conditions[0] is HasTag single
            ? (IReadOnlySet<ITagCondition>)new HashSet<ITagCondition> { single }
            : conditions.ToHashSet();
    }

    private static IReadOnlySet<string> ParseTagList(string inner)
    {
        inner = inner.Trim().Trim('[', ']').Trim();
        var set = new HashSet<string>();
        foreach (var raw in inner.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (raw.StartsWith("Lv", StringComparison.OrdinalIgnoreCase)) continue; // LvN 交由层级条件处理
            set.Add(raw);
        }
        return set;
    }

    /// <summary>
    /// HasAllTags 的完整解析：普通标签用 HasAllTags，层级项单独包装后用 AND 组合。
    /// </summary>
    private ITagCondition ParseHasAllTagsFull(string expr)
    {
        var conditions = ParseTagListAsConditions(Inner(expr, "HasAllTags("));
        return conditions.Count == 1 ? conditions.First() : new AndCondition(conditions.ToList());
    }

    private ITagCondition ParseTierAtLeast(string expr)
    {
        // "TierAtLeast(火焰,5)" → TagTierAtLeast
        var inner = Inner(expr, "TierAtLeast(").Trim();
        var parts = inner.Split(',', StringSplitOptions.TrimEntries);
        return new TagTierAtLeast(parts[0], int.Parse(parts[1]));
    }

    private static string Inner(string expr, string prefix)
        => expr[prefix.Length..^1];

    private static int IndexOfOutsideParens(string s, string token)
    {
        int depth = 0;
        for (int i = 0; i <= s.Length - token.Length; i++)
        {
            var ch = s[i];
            if (ch == '(' || ch == '[') depth++;
            else if (ch == ')' || ch == ']') depth--;
            else if (depth == 0 && string.CompareOrdinal(s, i, token, 0, token.Length) == 0)
                return i;
        }
        return -1;
    }

    private static List<string> SplitOutsideParens(string s, string token)
    {
        var parts = new List<string>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.Length; i++)
        {
            var ch = s[i];
            if (ch == '(' || ch == '[') depth++;
            else if (ch == ')' || ch == ']') depth--;
            else if (depth == 0 && i + token.Length <= s.Length
                     && string.CompareOrdinal(s, i, token, 0, token.Length) == 0)
            {
                parts.Add(s[start..i]);
                i += token.Length - 1;
                start = i + 1;
            }
        }
        parts.Add(s[start..]);
        return parts;
    }
}

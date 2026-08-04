using System.Text.Json;
using GameCore.Tag;

namespace GameCore.Effect;

/// <summary>
/// EffectParser — 按 type 路由解析效果 JSON。
/// STAT_MOD / DAMAGE_MOD / TRIGGER / FLAG。
/// </summary>
public sealed class EffectParser
{
    private readonly TagConditionParser _conditionParser;

    public EffectParser(TagConditionParser conditionParser)
    {
        _conditionParser = conditionParser;
    }

    public IEffectDef Parse(JsonElement node)
    {
        var type = node.GetProperty("type").GetString();
        return type switch
        {
            "STAT_MOD" => ParseStatMod(node),
            "DAMAGE_MOD" => ParseDamageMod(node),
            "TRIGGER" => ParseTrigger(node),
            "FLAG" => ParseFlag(node),
            _ => throw new ArgumentException($"未知效果类型: {type}")
        };
    }

    /// <summary>解析节点中的 effects 数组；无该字段时返回空列表。</summary>
    public IReadOnlyList<IEffectDef> ParseEffects(JsonElement node)
    {
        var list = new List<IEffectDef>();
        if (!node.TryGetProperty("effects", out var arr)) return list;
        foreach (var e in arr.EnumerateArray()) list.Add(Parse(e));
        return list;
    }

    private static StatMod ParseStatMod(JsonElement n) => new(
        n.GetProperty("target").GetString(),
        ParseOperator(n.GetProperty("operator").GetString()),
        (float)n.GetProperty("value").GetDouble());

    private static DamageMod ParseDamageMod(JsonElement n) => new(
        n.TryGetProperty("againstTag", out var at) ? at.GetString() : null,
        n.TryGetProperty("onGridPos", out var gp) ? gp.GetInt32() : -1,
        ParseOperator(n.GetProperty("operator").GetString()),
        (float)n.GetProperty("value").GetDouble());

    private TriggerDef ParseTrigger(JsonElement n)
    {
        ITagCondition cond = n.TryGetProperty("condition", out var c)
            ? _conditionParser.Parse(c.GetString())
            : null;
        var parameters = new Dictionary<string, object>();
        if (n.TryGetProperty("params", out var p))
        {
            foreach (var f in p.EnumerateObject())
                parameters[f.Name] = ParseParamValue(f.Value);
        }
        return new TriggerDef(
            n.GetProperty("on").GetString(),
            n.TryGetProperty("chance", out var ch) ? ch.GetDouble() : 1.0,
            n.GetProperty("action").GetString(),
            parameters,
            cond);
    }

    private static FlagDef ParseFlag(JsonElement n) => new(
        n.TryGetProperty("flagName", out var f) ? f.GetString() : null);

    private static object ParseParamValue(JsonElement v) => v.ValueKind switch
    {
        JsonValueKind.Number when v.TryGetInt32(out var i) => i,
        JsonValueKind.Number => v.GetDouble(),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        _ => v.GetString()
    };

    public static Operator ParseOperator(string raw) => raw.ToUpperInvariant() switch
    {
        "ADD" => Operator.Add,
        "MULTIPLY" => Operator.Multiply,
        "SET" => Operator.Set,
        _ => throw new ArgumentException($"未知运算符: {raw}")
    };
}

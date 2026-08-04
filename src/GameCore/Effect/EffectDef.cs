namespace GameCore.Effect;

/// <summary>效果类型枚举：STAT_MOD / DAMAGE_MOD / TRIGGER / FLAG。</summary>
public enum EffectType { StatMod, DamageMod, Trigger, Flag }

/// <summary>数值运算符：ADD / MULTIPLY / SET。</summary>
public enum Operator { Add, Multiply, Set }

/// <summary>效果密封接口（设计文档中的 sealed interface EffectDef）。</summary>
public interface IEffectDef
{
    EffectType Type { get; }
}

/// <summary>属性修正效果。target 为属性名（ATK/DEF/HP/DARK_ATK...）。</summary>
public sealed record StatMod(string Target, Operator Operator, float Value) : IEffectDef
{
    public EffectType Type => EffectType.StatMod;

    public float Apply(float baseValue) => Operator switch
    {
        Operator.Add => baseValue + Value,
        Operator.Multiply => baseValue * Value,
        Operator.Set => Value,
        _ => baseValue
    };
}

/// <summary>条件增伤效果。againstTag / againstGridRow 为 null / -1 表示无条件。</summary>
public sealed record DamageMod(
    string AgainstTag,
    int AgainstGridRow,
    Operator Operator,
    float Value) : IEffectDef
{
    public EffectType Type => EffectType.DamageMod;

    public bool Matches(Unit.Unit target)
    {
        if (AgainstTag != null && !target.HasTag(AgainstTag)) return false;
        if (AgainstGridRow >= 0 && target.GridPos.Row != AgainstGridRow) return false;
        return true;
    }
}

/// <summary>事件触发效果。on = 事件类型；action = GAIN_BUFF / HEAL / GAIN_TAG / EMIT_EVENT。</summary>
public sealed record TriggerDef(
    string On,
    double Chance,
    string Action,
    Dictionary<string, object> Params,
    Tag.ITagCondition Condition) : IEffectDef
{
    public EffectType Type => EffectType.Trigger;

    public bool ShouldFire(EventBus.Event evt, Unit.Unit owner, Random rng)
    {
        if (Condition != null && !Condition.Evaluate(owner.ActiveTagIds)) return false;
        return rng.NextDouble() < Chance;
    }
}

/// <summary>纯标记效果，供其他标签做条件引用。</summary>
public sealed record FlagDef(string FlagName) : IEffectDef
{
    public EffectType Type => EffectType.Flag;
}

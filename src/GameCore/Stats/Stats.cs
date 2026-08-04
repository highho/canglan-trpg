using GameCore.Effect;

namespace GameCore.Stats;

/// <summary>
/// StatValue — 单个属性的效果累积快照（ADD 累加 / MULTIPLY 累乘 / SET 覆盖）。
/// 标签层与 Buff 层各自维护一份，查询时合并。
/// </summary>
public sealed class StatValue
{
    public float Add { get; private set; }
    public float Multiply { get; private set; } = 1f;
    public float? Set { get; private set; }

    public void Apply(Operator op, float value)
    {
        switch (op)
        {
            case Operator.Add: Add += value; break;
            case Operator.Multiply: Multiply *= value; break;
            case Operator.Set: Set = value; break;
        }
    }

    public void Revert(Operator op, float value)
    {
        switch (op)
        {
            case Operator.Add: Add -= value; break;
            case Operator.Multiply:
                if (Math.Abs(value) > 1e-6f) Multiply /= value;
                break;
            case Operator.Set: Set = null; break;
        }
    }

    /// <summary>SET 优先；否则 (base + ADD) × MULTIPLY。</summary>
    public float ApplyTo(float baseValue)
        => Set.HasValue ? Set.Value : (baseValue + Add) * Multiply;
}

/// <summary>
/// Stats — Unit 基础属性容器。HP 键语义 = 生命上限；当前血量单独存于 Hp。
/// </summary>
public sealed class Stats
{
    public int MaxHp { get; set; } = 100;
    public int Hp { get; set; } = 100;
    public float Atk { get; set; } = 10f;
    public float Def { get; set; } = 5f;
    public float Spd { get; set; } = 10f;
    public float CritRate { get; set; } = 0.05f;

    public bool IsHpEmpty => Hp <= 0;

    /// <summary>按属性键读取基础值（未含标签/Buff修正）。</summary>
    public float GetBase(string key) => key.ToUpperInvariant() switch
    {
        "HP" or "MAXHP" or "MAX_HP" => MaxHp,
        "ATK" => Atk,
        "DEF" => Def,
        "SPD" => Spd,
        "CRIT" or "CRITRATE" => CritRate,
        _ => 0f
    };

    /// <summary>按属性键写入基础值（角色创建/升级用）。</summary>
    public void SetBase(string key, float value)
    {
        switch (key.ToUpperInvariant())
        {
            case "HP": case "MAXHP": case "MAX_HP":
                MaxHp = (int)value;
                Hp = Math.Min(Hp == 0 ? MaxHp : Hp, MaxHp);
                if (Hp <= 0) Hp = MaxHp;
                break;
            case "ATK": Atk = value; break;
            case "DEF": Def = value; break;
            case "SPD": Spd = value; break;
            case "CRIT": case "CRITRATE": CritRate = value; break;
        }
    }
}

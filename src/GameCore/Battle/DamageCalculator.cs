using GameCore.Effect;

namespace GameCore.Battle;

/// <summary>
/// DamageCalculator — 五层伤害计算。
/// 第一层 标签效果（ATK 标签快照）→ 第二层 Buff效果 → 已由 Unit.GetStat 统一合并（同类效果叠加）；
/// 第三层 站位修正（前排+15%）；第四层 克制关系（标签/Buff 的 DAMAGE_MOD）；第五层 防御减免。
/// </summary>
public static class DamageCalculator
{
    /// <summary>普攻伤害。返回最终伤害与是否暴击（暴击 ×1.5）。</summary>
    public static (float Damage, bool IsCrit) Calculate(
        Unit.Unit attacker, Unit.Unit defender, GridSystem grid, Random rng)
    {
        // 第一层 + 第二层：基础ATK + 标签快照 + Buff快照（Unit.GetStat 统一查询，同类效果叠加）
        var damage = attacker.Atk;

        // 第三层：站位修正
        damage *= GridSystem.GetPositionModifier(attacker.GridPos);

        // 第四层：克制关系（标签间 DAMAGE_MOD + Buff DAMAGE_MOD）
        damage *= GetTagDamageModMultiplier(attacker, defender);
        damage *= attacker.BuffManager.GetDamageModMultiplier(defender);

        // 第五层：防御减免
        damage -= defender.Def;

        // 暴击判定
        var crit = rng.NextDouble() < attacker.GetStat("CRIT");
        if (crit) damage *= 1.5f;

        return (Math.Max(1f, damage), crit);
    }

    /// <summary>技能伤害（base = skill.BaseDamage，其余层同上）。</summary>
    public static (float Damage, bool IsCrit) CalculateSkill(
        Unit.Unit attacker, Skill.Skill skill, Unit.Unit defender, GridSystem grid, Random rng)
    {
        var damage = (float)skill.BaseDamage + attacker.Atk * 0.5f;
        damage *= GridSystem.GetPositionModifier(attacker.GridPos);
        damage *= GetTagDamageModMultiplier(attacker, defender);
        damage *= attacker.BuffManager.GetDamageModMultiplier(defender);
        if (skill.DamageType != Skill.DamageType.True)
            damage -= defender.Def;

        var crit = rng.NextDouble() < attacker.GetStat("CRIT");
        if (crit) damage *= 1.5f;
        return (Math.Max(1f, damage), crit);
    }

    /// <summary>第四层：攻击方活跃标签中的 DAMAGE_MOD 效果对防御方的倍率。</summary>
    private static float GetTagDamageModMultiplier(Unit.Unit attacker, Unit.Unit defender)
    {
        var multiplier = 1f;
        foreach (var tag in attacker.ActiveTags)
        {
            foreach (var effect in tag.Effects)
            {
                if (effect is DamageMod dm && dm.Matches(defender))
                {
                    multiplier = dm.Operator switch
                    {
                        Operator.Add => multiplier + dm.Value,
                        Operator.Multiply => multiplier * dm.Value,
                        Operator.Set => dm.Value,
                        _ => multiplier
                    };
                }
            }
        }
        return multiplier;
    }
}

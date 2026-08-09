package com.canglan.core.effect;

/**
 * 条件增伤效果。againstTag / againstGridRow 为 null / -1 表示无条件。
 * 对应 C# DamageMod（matches 参数改为 EffectTarget 解耦 Unit）。
 */
public record DamageMod(String againstTag, int againstGridRow, Operator operator, float value)
        implements EffectDef {
    @Override
    public EffectType type() { return EffectType.DAMAGE_MOD; }

    public boolean matches(EffectTarget target) {
        if (againstTag != null && !target.hasTag(againstTag)) return false;
        if (againstGridRow >= 0 && target.gridRow() != againstGridRow) return false;
        return true;
    }
}

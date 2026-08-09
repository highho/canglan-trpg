package com.canglan.core.effect;

/** 属性修正效果。target 为属性名（ATK/DEF/HP/...）。对应 C# StatMod。 */
public record StatMod(String target, Operator operator, float value) implements EffectDef {
    @Override
    public EffectType type() { return EffectType.STAT_MOD; }

    public float apply(float baseValue) {
        return switch (operator) {
            case ADD -> baseValue + value;
            case MULTIPLY -> baseValue * value;
            case SET -> value;
        };
    }
}

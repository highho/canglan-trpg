package com.canglan.core.effect;

/** 纯标记效果，供其他标签做条件引用。对应 C# FlagDef。 */
public record FlagDef(String flagName) implements EffectDef {
    @Override
    public EffectType type() { return EffectType.FLAG; }
}

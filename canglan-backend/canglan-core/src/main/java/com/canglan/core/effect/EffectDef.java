package com.canglan.core.effect;

/** 效果密封接口。对应 C# IEffectDef（sealed interface）。 */
public sealed interface EffectDef permits StatMod, DamageMod, TriggerDef, FlagDef {
    EffectType type();
}

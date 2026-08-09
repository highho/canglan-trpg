package com.canglan.core.effect;

import java.util.Map;
import java.util.Random;

import com.canglan.core.eventbus.Event;
import com.canglan.core.tag.TagCondition;

/**
 * 事件触发效果。on = 事件类型；action = GAIN_BUFF / HEAL / GAIN_TAG / EMIT_EVENT。
 * 对应 C# TriggerDef（owner 参数改为 EffectTarget 解耦 Unit）。
 */
public record TriggerDef(String on, double chance, String action,
                         Map<String, Object> params, TagCondition condition) implements EffectDef {
    @Override
    public EffectType type() { return EffectType.TRIGGER; }

    public boolean shouldFire(Event evt, EffectTarget owner, Random rng) {
        if (condition != null && !condition.evaluate(owner.activeTagIds())) return false;
        return rng.nextDouble() < chance;
    }
}

package com.canglan.world.stats;

import com.canglan.core.effect.Operator;

/**
 * StatValue — 单个属性的效果累积快照（ADD 累加 / MULTIPLY 累乘 / SET 覆盖）。
 * 标签层与 Buff 层各自维护一份，查询时合并。对应 C# StatValue。
 */
public final class StatValue {

    private float add;
    private float multiply = 1f;
    private Float set;              // null = 未设置

    public float add() { return add; }
    public float multiply() { return multiply; }
    public Float set() { return set; }

    public void apply(Operator op, float value) {
        switch (op) {
            case ADD -> add += value;
            case MULTIPLY -> multiply *= value;
            case SET -> set = value;
        }
    }

    public void revert(Operator op, float value) {
        switch (op) {
            case ADD -> add -= value;
            case MULTIPLY -> {
                if (Math.abs(value) > 1e-6f) multiply /= value;
            }
            case SET -> set = null;
        }
    }

    /** SET 优先；否则 (base + ADD) × MULTIPLY。 */
    public float applyTo(float baseValue) {
        return set != null ? set : (baseValue + add) * multiply;
    }
}

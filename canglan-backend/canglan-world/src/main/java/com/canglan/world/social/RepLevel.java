package com.canglan.world.social;

/** 阵营声望等级。对应 C# RepLevel。 */
public enum RepLevel {
    HOSTILE(-1), NEUTRAL(0), FRIENDLY(1), HONORED(2), REVERED(3);

    private final int value;

    RepLevel(int value) { this.value = value; }

    public int value() { return value; }
}

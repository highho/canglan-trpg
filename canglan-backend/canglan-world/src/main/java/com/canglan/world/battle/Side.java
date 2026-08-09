package com.canglan.world.battle;

/** 阵营。对应 C# Side。 */
public enum Side {
    ALLY, ENEMY;

    public Side opposite() { return this == ALLY ? ENEMY : ALLY; }
}

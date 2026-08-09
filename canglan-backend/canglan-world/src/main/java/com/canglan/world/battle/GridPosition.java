package com.canglan.world.battle;

/**
 * 格位坐标：row 1=前排 2=后排；col 1=左 2=中 3=右。对应 C# GridPosition。
 */
public record GridPosition(int row, int col, Side side) {

    public Side opposite() { return side.opposite(); }
}

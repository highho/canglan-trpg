package com.canglan.world.unit;

/** 九宫格站位（行0-2，列0-2）。站位修正与 DAMAGE_MOD.againstGridRow 使用。对应 C# GridPos。 */
public record GridPos(int row, int col) {
}

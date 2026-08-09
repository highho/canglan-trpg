package com.canglan.world.battle;

import java.util.List;

import com.canglan.world.unit.Unit;

/** 战斗结果。对应 C# BattleResult。 */
public record BattleResult(boolean playerWin, List<Unit> deaths, List<Unit> survivors) {
}

package com.canglan.world;

/** 迷雾格子状态：未探索(黑) → 已探索(半透明) → 可见。对应 C# CellState。 */
public enum CellState {
    UNEXPLORED, EXPLORED, VISIBLE
}

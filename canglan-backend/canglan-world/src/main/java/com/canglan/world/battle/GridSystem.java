package com.canglan.world.battle;

import java.util.ArrayList;
import java.util.List;

import com.canglan.data.skill.TargetPattern;
import com.canglan.world.unit.GridPos;
import com.canglan.world.unit.Unit;

/**
 * GridSystem — 双九宫格管理（3×3×2）。纯数据结构+计算，无外部依赖。
 * 站位效果：前列增减伤、相邻分担。对应 C# GridSystem。
 */
public final class GridSystem {

    // [side][row][col]
    private final Unit[][][] grid = new Unit[2][2][3];

    public void placeUnit(Unit unit, GridPosition pos) {
        grid[pos.side().ordinal()][pos.row() - 1][pos.col() - 1] = unit;
        unit.setGridPos(new GridPos(pos.row(), pos.col()));
    }

    public void removeUnit(Unit unit) {
        for (int s = 0; s < 2; s++)
            for (int r = 0; r < 2; r++)
                for (int c = 0; c < 3; c++)
                    if (grid[s][r][c] == unit)
                        grid[s][r][c] = null;
    }

    public void swapUnits(Unit a, Unit b) {
        GridPosition pa = findPosition(a);
        GridPosition pb = findPosition(b);
        if (pa == null || pb == null) return;
        removeUnit(a);
        removeUnit(b);
        placeUnit(a, pb);
        placeUnit(b, pa);
    }

    public Unit getAt(GridPosition pos) {
        return grid[pos.side().ordinal()][pos.row() - 1][pos.col() - 1];
    }

    public GridPosition findPosition(Unit unit) {
        for (int s = 0; s < 2; s++)
            for (int r = 0; r < 2; r++)
                for (int c = 0; c < 3; c++)
                    if (grid[s][r][c] == unit)
                        return new GridPosition(r + 1, c + 1, Side.values()[s]);
        return null;
    }

    public List<Unit> getRow(Side side, int row) {
        List<Unit> list = new ArrayList<>();
        for (int c = 0; c < 3; c++)
            if (grid[side.ordinal()][row - 1][c] != null) list.add(grid[side.ordinal()][row - 1][c]);
        return list;
    }

    public List<Unit> getColumn(Side side, int col) {
        List<Unit> list = new ArrayList<>();
        for (int r = 0; r < 2; r++)
            if (grid[side.ordinal()][r][col - 1] != null) list.add(grid[side.ordinal()][r][col - 1]);
        return list;
    }

    public List<Unit> getAll(Side side) {
        List<Unit> list = new ArrayList<>();
        for (int r = 0; r < 2; r++)
            for (int c = 0; c < 3; c++)
                if (grid[side.ordinal()][r][c] != null) list.add(grid[side.ordinal()][r][c]);
        return list;
    }

    /** 按目标模式取目标集（origin 所在阵营/行列决定作用范围；ALL 取对面全场）。 */
    public List<Unit> getTargets(GridPosition origin, TargetPattern pattern) {
        switch (pattern) {
            case SINGLE: {
                Unit at = getAt(origin);
                List<Unit> list = new ArrayList<>();
                if (at != null) list.add(at);
                return list;
            }
            case ROW: return getRow(origin.side(), origin.row());
            case COLUMN: return getColumn(origin.side(), origin.col());
            case ALL: return getAll(origin.opposite());
            case SELF: {
                Unit self = getAt(origin);
                List<Unit> list = new ArrayList<>();
                if (self != null) list.add(self);
                return list;
            }
            case ADJACENT: return getAdjacent(origin);
            default: return new ArrayList<>();
        }
    }

    private List<Unit> getAdjacent(GridPosition origin) {
        List<Unit> list = new ArrayList<>();
        int[][] deltas = { {0, -1}, {0, 1}, {-1, 0}, {1, 0} };
        for (int[] d : deltas) {
            int r = origin.row() - 1 + d[0];
            int c = origin.col() - 1 + d[1];
            if (r >= 0 && r < 2 && c >= 0 && c < 3) {
                Unit u = grid[origin.side().ordinal()][r][c];
                if (u != null) list.add(u);
            }
        }
        return list;
    }

    /** 前排 +15% 伤害修正。 */
    public static float getPositionModifier(GridPos pos) {
        return pos.row() == 1 ? 1.15f : 1.0f;
    }

    /** 相邻友方（同行左右邻，分担伤害/掩护用）。 */
    public List<Unit> getAdjacentAllies(Unit unit) {
        List<Unit> allies = new ArrayList<>();
        GridPosition p = findPosition(unit);
        if (p == null) return allies;
        for (int dc : new int[] { -1, 1 }) {
            int nc = p.col() - 1 + dc;
            if (nc >= 0 && nc < 3) {
                Unit adj = grid[p.side().ordinal()][p.row() - 1][nc];
                if (adj != null && adj != unit) allies.add(adj);
            }
        }
        return allies;
    }
}

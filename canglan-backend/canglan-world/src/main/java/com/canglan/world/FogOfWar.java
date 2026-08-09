package com.canglan.world;

import java.util.ArrayList;
import java.util.List;

import com.canglan.world.unit.Unit;

/**
 * FogOfWar — 战争迷雾。对应 C# FogOfWar。
 * 圆形视野（dx²+dy² ≤ range²），视野离开后 VISIBLE → EXPLORED。
 * 视野范围由基础值 + 标签修正（[夜视]/[鹰眼] 等 VISION 效果）。
 */
public final class FogOfWar {

    private final int width;
    private final int height;
    private int visionRange;
    private final CellState[][] states;

    public int width() { return width; }
    public int height() { return height; }
    public int visionRange() { return visionRange; }
    public void setVisionRange(int v) { this.visionRange = v; }

    public FogOfWar(int width, int height, int baseVision) {
        this.width = width;
        this.height = height;
        this.visionRange = baseVision;
        this.states = new CellState[height][width];   // 缺省 null = UNEXPLORED
    }

    /** 根据 Unit 位置 + 视野更新迷雾。 */
    public void update(Unit unit) {
        int range = visionRange + unit.getVisionBonus();
        MapPos pos = unit.worldPos();
        if (pos == null) return;
        for (int dy = -range; dy <= range; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                int nx = pos.x() + dx;
                int ny = pos.y() + dy;
                if (inBounds(nx, ny) && dx * dx + dy * dy <= range * range) {
                    states[ny][nx] = CellState.VISIBLE;
                }
            }
        }
    }

    /** 视野离开后：VISIBLE → EXPLORED（半透明）。 */
    public void decayAfterMove(Unit unit) {
        int range = visionRange + unit.getVisionBonus();
        MapPos pos = unit.worldPos();
        if (pos == null) return;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (states[y][x] == CellState.VISIBLE && distance(pos.x(), pos.y(), x, y) > range) {
                    states[y][x] = CellState.EXPLORED;
                }
            }
        }
    }

    public CellState get(int x, int y) { return inBounds(x, y) ? cellAt(x, y) : CellState.UNEXPLORED; }

    public boolean isVisible(int x, int y) { return inBounds(x, y) && states[y][x] == CellState.VISIBLE; }

    /** 存档导出：每行 → "U/E/V" 字符序列。 */
    public List<FogRow> exportRows() {
        List<FogRow> rows = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            char[] chars = new char[width];
            for (int x = 0; x < width; x++) {
                chars[x] = switch (cellAt(x, y)) {
                    case VISIBLE -> 'V';
                    case EXPLORED -> 'E';
                    default -> 'U';
                };
            }
            rows.add(new FogRow(y, new String(chars)));
        }
        return rows;
    }

    /** 读档恢复迷雾。 */
    public void importRows(List<FogRow> rows) {
        if (rows == null) return;
        for (FogRow row : rows) {
            int y = row.y();
            String s = row.states();
            if (y < 0 || y >= height || s == null) continue;
            for (int x = 0; x < width && x < s.length(); x++) {
                states[y][x] = switch (s.charAt(x)) {
                    case 'V' -> CellState.VISIBLE;
                    case 'E' -> CellState.EXPLORED;
                    default -> CellState.UNEXPLORED;
                };
            }
        }
    }

    private CellState cellAt(int x, int y) {
        CellState s = states[y][x];
        return s != null ? s : CellState.UNEXPLORED;
    }

    private boolean inBounds(int x, int y) { return x >= 0 && x < width && y >= 0 && y < height; }

    private static double distance(int x1, int y1, int x2, int y2) {
        return Math.sqrt((double) (x1 - x2) * (x1 - x2) + (double) (y1 - y2) * (y1 - y2));
    }
}

package com.canglan.world.home;

import java.util.ArrayList;
import java.util.List;

import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.world.MapPos;
import com.canglan.world.unit.Unit;

/**
 * HomeBase — 家园。大地图的位置 + 内部网格放置建筑 + 家园等级（[家园LvN] 标签）。
 * 对应 C# HomeBase。HomeManager（NPC来访/入侵每日结算）不属于 32 指令，P6 不迁移。
 */
public final class HomeBase {

    private final MapPos position;
    private final int gridWidth;
    private final int gridHeight;
    private int level = 1;
    private final Unit owner;
    private final EventBus eventBus;

    private final Building[][] grid;
    private final List<Building> buildings = new ArrayList<>();

    public HomeBase(MapPos position, int width, int height, EventBus bus, Unit owner) {
        this.position = position;
        this.gridWidth = width;
        this.gridHeight = height;
        this.grid = new Building[height][width];
        this.eventBus = bus;
        this.owner = owner;
        syncHomeTag();
    }

    public MapPos position() { return position; }
    public int gridWidth() { return gridWidth; }
    public int gridHeight() { return gridHeight; }
    public int level() { return level; }
    public Unit owner() { return owner; }

    /** 放置建筑蓝图（边界/占位/前置建筑/标签条件检查）。 */
    public boolean placeBuilding(Building building, int x, int y) {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) return false;
        if (grid[y][x] != null) return false;
        String prereq = building.def().prerequisite();
        if (prereq != null && !hasBuilding(prereq)) return false;
        if (owner != null && !building.def().condition().evaluate(owner.activeTagIds()))
            return false;

        grid[y][x] = building;
        buildings.add(building);
        return true;
    }

    /** 读档恢复家园等级（重新同步 [家园LvN] 标签，覆盖构造时的 Lv1）。 */
    public void restoreLevel(int newLevel) {
        level = Math.max(1, newLevel);
        syncHomeTag();
    }

    /** 读档放置建筑（跳过前置/条件检查，直接入网格）。 */
    public boolean restoreBuilding(Building building, int x, int y) {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) return false;
        if (grid[y][x] != null) return false;
        grid[y][x] = building;
        buildings.add(building);
        return true;
    }

    /** 是否存在指定ID的已建成建筑。 */
    public boolean hasBuilding(String buildingId) {
        for (Building b : buildings)
            if (b.id().equals(buildingId) && b.isComplete()) return true;
        return false;
    }

    /** 拆除建筑（按 ID 移除，返回是否成功）。 */
    public boolean removeBuilding(String buildingId) {
        Building building = null;
        for (Building b : buildings) if (b.id().equals(buildingId)) { building = b; break; }
        if (building == null) return false;
        buildings.remove(building);
        for (int y = 0; y < gridHeight; y++)
            for (int x = 0; x < gridWidth; x++)
                if (grid[y][x] == building) grid[y][x] = null;
        return true;
    }

    /** 家园等级提升 → 发射 HOME_LEVEL_UP + 更新 [家园LvN] 标签。 */
    public void levelUp() {
        level++;
        syncHomeTag();
        eventBus.emit(EventTypes.HOME_LEVEL_UP, owner, level);
    }

    /** 同步 [家园LvN] 特质标签（移除旧层级，加入新层级）。 */
    private void syncHomeTag() {
        if (owner == null) return;
        owner.traitTagIds().removeIf(t -> t.startsWith("家园Lv"));
        owner.traitTagIds().add("家园Lv" + level);
        owner.recalculateTags();
    }

    /** 防御值 = 防御墙数量 × 等级 × 10。 */
    public int getDefenseValue() {
        int walls = 0;
        for (Building b : buildings)
            if ("DEFENSE".equals(b.category()) && b.isComplete()) walls++;
        return walls * level * 10;
    }

    public List<Building> getBuildings() { return new ArrayList<>(buildings); }

    public Building getAt(int x, int y) {
        return x >= 0 && x < gridWidth && y >= 0 && y < gridHeight ? grid[y][x] : null;
    }
}

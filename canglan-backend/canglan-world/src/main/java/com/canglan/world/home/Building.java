package com.canglan.world.home;

import com.canglan.data.home.BuildingDef;

/**
 * Building — 建筑实例。投入材料推进建造，进度达标 → COMPLETE。对应 C# Building。
 * 分类沿用数据层 BuildingDef.category（大写字符串：STORAGE/CRAFTING/REST/FARM/DEFENSE/UTILITY）。
 */
public final class Building {

    private final String id;
    private final String name;
    private final String category;
    private final BuildingDef def;
    private BuildingState state = BuildingState.BLUEPRINT;
    private int buildProgress;
    private final int buildTotal;
    private int level = 1;

    public Building(BuildingDef def) {
        this.def = def;
        this.id = def.id();
        this.name = def.name();
        this.category = def.category();
        int total = 0;
        for (int v : def.materials().values()) total += v;
        this.buildTotal = total;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String category() { return category; }
    public BuildingDef def() { return def; }
    public BuildingState state() { return state; }
    public int buildProgress() { return buildProgress; }
    public int buildTotal() { return buildTotal; }
    public int level() { return level; }
    public void setLevel(int v) { this.level = v; }
    public boolean isComplete() { return state == BuildingState.COMPLETE; }

    /** 投入材料推进建造。 */
    public boolean contribute(String materialId, int quantity) {
        if (state == BuildingState.COMPLETE) return false;
        if (!def.materials().containsKey(materialId)) return false;

        buildProgress += quantity;
        if (buildProgress >= buildTotal) {
            state = BuildingState.COMPLETE;
            buildProgress = buildTotal;
        } else {
            state = BuildingState.CONSTRUCTING;
        }
        return true;
    }

    /** 读档恢复建造状态。 */
    public void restoreState(BuildingState newState, int progress) {
        state = newState;
        buildProgress = Math.min(progress, buildTotal);
    }
}

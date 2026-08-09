package com.canglan.save;

/** 建筑存档。对应 C# BuildingSaveData。 */
public final class BuildingSaveData {
    public String buildingId;
    public int gridX;
    public int gridY;
    public int level;
    public String state;          // "Blueprint" / "Constructing" / "Complete"
    public int buildProgress;
}

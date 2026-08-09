package com.canglan.save;

import java.util.ArrayList;
import java.util.List;

/** 家园存档。对应 C# HomeSaveData。 */
public final class HomeSaveData {
    public int level;
    public int x;
    public int y;
    public int gridWidth;
    public int gridHeight;
    public List<BuildingSaveData> buildings = new ArrayList<>();
}

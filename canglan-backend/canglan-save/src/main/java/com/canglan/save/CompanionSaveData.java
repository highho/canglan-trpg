package com.canglan.save;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 队友存档（源头数据 → recalculateTags 重建）。对应 C# CompanionSaveData。 */
public final class CompanionSaveData {
    public String name;
    public String currentRaceId;
    public String currentClassId;
    public Set<String> questTagIds = new HashSet<>();
    public Set<String> traitTagIds = new HashSet<>();
    public int level = 1;
    public int exp;
    public String recruitmentType = "Bond";   // "Bond" / "Mercenary"
    public int contractRemaining = -1;        // 感情招募=-1
    public int affinity;                      // 对玩家好感度
    public Map<String, Integer> allyAffinities = new HashMap<>();   // 队友名 → 好感度
    public Map<String, Integer> inventory = new HashMap<>();
}

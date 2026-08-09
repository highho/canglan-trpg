package com.canglan.save;

import java.util.HashMap;
import java.util.Map;

/** 任务进度存档。对应 C# QuestSaveData。 */
public final class QuestSaveData {
    public String questId;
    public int currentStep;
    public Map<String, Boolean> flags = new HashMap<>();
    public int cooldownRemaining;
}

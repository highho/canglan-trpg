package com.canglan.world.social;

import java.util.HashMap;
import java.util.Map;

/**
 * ReputationSystem — 声望系统。factionId → unitId → 声望值。对应 C# ReputationSystem。
 */
public final class ReputationSystem {

    private final Map<String, Map<String, Integer>> factionReps = new HashMap<>();

    public int get(String factionId, String unitId) {
        Map<String, Integer> map = factionReps.get(factionId);
        return map == null ? 0 : map.getOrDefault(unitId, 0);
    }

    public void adjust(String factionId, String unitId, int delta) {
        factionReps.computeIfAbsent(factionId, k -> new HashMap<>())
                .merge(unitId, delta, Integer::sum);
    }

    public Rank getRank(String factionId, String unitId) {
        return Rank.fromReputation(get(factionId, unitId));
    }

    /** 声望折扣。 */
    public float getDiscount(String factionId, String unitId) {
        return getRank(factionId, unitId).discount();
    }

    /** 声望级别比较（&gt;=0 表示达到 rankName）。 */
    public int compareRank(int reputation, String rankName) {
        return Rank.fromReputation(reputation).compareTo(Rank.fromName(rankName));
    }

    /** 存档导出：factionId → { unitId → rep }。 */
    public Map<String, Map<String, Integer>> toSaveMap() {
        Map<String, Map<String, Integer>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> kv : factionReps.entrySet())
            copy.put(kv.getKey(), new HashMap<>(kv.getValue()));
        return copy;
    }

    public void loadFrom(Map<String, Map<String, Integer>> map) {
        factionReps.clear();
        if (map == null) return;
        for (Map.Entry<String, Map<String, Integer>> kv : map.entrySet())
            factionReps.put(kv.getKey(), new HashMap<>(kv.getValue()));
    }
}

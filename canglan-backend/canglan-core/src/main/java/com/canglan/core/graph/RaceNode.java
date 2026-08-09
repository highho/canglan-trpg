package com.canglan.core.graph;

import java.util.Map;
import java.util.Set;

/** 种族节点（种族进化图 = DAG，不可逆）。对应 C# RaceNode。 */
public final class RaceNode extends GraphNode<RaceData> {

    public RaceNode(String id, RaceData data) {
        super(id, data);
    }

    public String name() { return data().name(); }
    public Set<String> tagIds() { return data().tagIds(); }
    public Map<String, Double> baseStats() { return data().baseStats(); }
    public Set<String> conflictTags() { return data().conflictTags(); }
}

package com.canglan.core.graph;

import java.util.Map;
import java.util.Set;

/** 职业节点（职业转职图 = DAG，可降级转回）。对应 C# ClassNode。 */
public final class ClassNode extends GraphNode<ClassData> {

    public ClassNode(String id, ClassData data) {
        super(id, data);
    }

    public String name() { return data().name(); }
    public Set<String> tagIds() { return data().tagIds(); }
    public String skillTreeRoot() { return data().skillTreeRoot(); }
    public Map<String, Double> statGrowth() { return data().statGrowth(); }
}

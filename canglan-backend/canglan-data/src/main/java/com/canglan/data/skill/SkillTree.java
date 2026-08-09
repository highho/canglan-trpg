package com.canglan.data.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.canglan.core.graph.Edge;
import com.canglan.core.graph.GraphEngine;
import com.canglan.core.graph.GraphNode;

/**
 * SkillTree — 职业技能树。根节点（无入边）职业转职时直接获得；
 * 其余节点由入边标签条件解锁（checkUnlocks 在标签变化后调用）。
 * 对应 C# SkillTree。
 */
public final class SkillTree {

    private final String id;
    private final GraphEngine<SkillData> graph;
    private final Set<String> unlockedSkillIds = new HashSet<>();

    public SkillTree(String id, GraphEngine<SkillData> graph) {
        this.id = id;
        this.graph = graph;
    }

    public String id() { return id; }
    public GraphEngine<SkillData> graph() { return graph; }
    public Set<String> unlockedSkillIds() { return unlockedSkillIds; }

    /** 转职时解锁全部根节点（无入边的技能）。 */
    public List<Skill> unlockRoots() {
        List<Skill> newly = new ArrayList<>();
        for (GraphNode<SkillData> node : graph.allNodes()) {
            if (!node.incomingEdges().isEmpty()) continue;
            if (unlockedSkillIds.add(node.id())) {
                newly.add(node.data().skill());
            }
        }
        return newly;
    }

    /** 检查是否有新技能可解锁（任一入边条件满足）。 */
    public List<Skill> checkUnlocks(Set<String> tagIds) {
        List<Skill> newlyUnlocked = new ArrayList<>();
        for (GraphNode<SkillData> node : graph.allNodes()) {
            if (unlockedSkillIds.contains(node.id())) continue;
            if (node.incomingEdges().isEmpty()) continue;
            for (Edge<SkillData> edge : node.incomingEdges()) {
                if (edge.condition() == null || edge.condition().evaluate(tagIds)) {
                    unlockedSkillIds.add(node.id());
                    newlyUnlocked.add(node.data().skill());
                    break;
                }
            }
        }
        return newlyUnlocked;
    }

    public List<Skill> getUnlockedSkills() {
        List<Skill> result = new ArrayList<>();
        for (String id : unlockedSkillIds) {
            GraphNode<SkillData> node = graph.getNode(id);
            if (node != null) result.add(node.data().skill());
        }
        return result;
    }

    /** 存档恢复：直接设置已解锁集合。 */
    public void restoreUnlocks(Iterable<String> skillIds) {
        unlockedSkillIds.clear();
        for (String id : skillIds) unlockedSkillIds.add(id);
    }
}

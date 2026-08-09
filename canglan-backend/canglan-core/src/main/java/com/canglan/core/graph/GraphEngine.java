package com.canglan.core.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.core.tag.EvalContext;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;

/**
 * 统一图引擎：可用边查询 / 可遍历检查 / BFS最短路径 / 冲突检测。
 * 对应 C# GraphEngine&lt;T&gt;。
 */
public final class GraphEngine<T> {

    private final Map<String, GraphNode<T>> nodesById = new HashMap<>();
    private final List<GraphNode<T>> allNodes = new ArrayList<>();
    private final TagConditionParser conditionParser;

    public GraphEngine(TagConditionParser parser) {
        this.conditionParser = parser;
    }

    public TagConditionParser conditionParser() { return conditionParser; }
    public List<GraphNode<T>> allNodes() { return allNodes; }

    public void addNode(GraphNode<T> node) {
        nodesById.put(node.id(), node);
        allNodes.add(node);
    }

    /** 不存在返回 null。 */
    public GraphNode<T> getNode(String id) {
        return nodesById.get(id);
    }

    /** 连接两节点；bidirectional 时同时创建反向边（职业图允许降级转回）。 */
    public Edge<T> connect(String fromId, String toId, TagCondition condition,
                           Map<String, Object> requirements, boolean bidirectional, String description) {
        GraphNode<T> from = getNode(fromId);
        GraphNode<T> to = getNode(toId);
        if (from == null) throw new IllegalArgumentException("未知节点: " + fromId);
        if (to == null) throw new IllegalArgumentException("未知节点: " + toId);
        Edge<T> edge = new Edge<>(from, to, condition, requirements, bidirectional, description);
        from.outgoingEdges().add(edge);
        to.incomingEdges().add(edge);
        if (bidirectional) {
            Edge<T> reverse = new Edge<>(to, from, condition, edge.requirements(), true, description);
            to.outgoingEdges().add(reverse);
            from.incomingEdges().add(reverse);
        }
        return edge;
    }

    /** 获得当前节点所有满足条件的出边。 */
    public List<Edge<T>> getAvailableEdges(GraphNode<T> current, Set<String> tagIds, EvalContext ctx) {
        List<Edge<T>> result = new ArrayList<>();
        if (current == null) return result;
        for (Edge<T> edge : current.outgoingEdges()) {
            if (edge.isAvailable(tagIds, ctx)) result.add(edge);
        }
        return result;
    }

    /** 获得所有可达节点（从任意节点出发，满足条件的目标节点）。进化预览UI用。 */
    public List<GraphNode<T>> getAvailableNodes(Set<String> tagIds, EvalContext ctx) {
        List<GraphNode<T>> result = new ArrayList<>();
        for (GraphNode<T> node : allNodes) {
            for (Edge<T> edge : node.outgoingEdges()) {
                if (edge.isAvailable(tagIds, ctx) && !result.contains(edge.to())) {
                    result.add(edge.to());
                }
            }
        }
        return result;
    }

    /** 检查特定边是否满足条件。 */
    public boolean canTraverse(String fromNodeId, String toNodeId, Set<String> tagIds, EvalContext ctx) {
        GraphNode<T> from = getNode(fromNodeId);
        if (from == null) return false;
        for (Edge<T> edge : from.outgoingEdges()) {
            if (edge.to().id().equals(toNodeId) && edge.isAvailable(tagIds, ctx)) return true;
        }
        return false;
    }

    /** BFS 最短路径（边序列）。不可达返回空表。 */
    public List<Edge<T>> shortestPath(GraphNode<T> from, GraphNode<T> to) {
        if (from == null || to == null) return new ArrayList<>();
        Deque<GraphNode<T>> queue = new ArrayDeque<>();
        Map<GraphNode<T>, Edge<T>> cameFrom = new IdentityHashMap<>();
        Set<GraphNode<T>> visited = new HashSet<>();
        visited.add(from);
        queue.add(from);

        while (!queue.isEmpty()) {
            GraphNode<T> current = queue.poll();
            if (current == to) break;
            for (Edge<T> edge : current.outgoingEdges()) {
                if (visited.add(edge.to())) {
                    cameFrom.put(edge.to(), edge);
                    queue.add(edge.to());
                }
            }
        }
        return reconstructPath(cameFrom, to);
    }

    private static <T> List<Edge<T>> reconstructPath(Map<GraphNode<T>, Edge<T>> cameFrom, GraphNode<T> to) {
        List<Edge<T>> path = new ArrayList<>();
        GraphNode<T> current = to;
        Edge<T> edge;
        while ((edge = cameFrom.get(current)) != null) {
            path.add(0, edge);
            current = edge.from();
        }
        return path;
    }

    /** 冲突检测：新节点自带的 conflictTags 与当前标签集冲突时返回交集。 */
    public Set<String> detectConflicts(GraphNode<T> newNode, Set<String> currentTagIds) {
        if (newNode.data() instanceof RaceData rd) {
            Set<String> conflicts = new HashSet<>(rd.conflictTags());
            conflicts.retainAll(currentTagIds);
            return conflicts;
        }
        return new HashSet<>();
    }
}

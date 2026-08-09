package com.canglan.core.graph;

import java.util.ArrayList;
import java.util.List;

/** 统一图节点。种族进化图/职业转职图/任务图共用。对应 C# GraphNode&lt;T&gt;。 */
public class GraphNode<T> {
    private final String id;
    private T data;
    private final List<Edge<T>> outgoingEdges = new ArrayList<>();
    private final List<Edge<T>> incomingEdges = new ArrayList<>();

    public GraphNode(String id, T data) {
        this.id = id;
        this.data = data;
    }

    public String id() { return id; }
    public T data() { return data; }
    public void setData(T data) { this.data = data; }
    public List<Edge<T>> outgoingEdges() { return outgoingEdges; }
    public List<Edge<T>> incomingEdges() { return incomingEdges; }

    @Override
    public String toString() { return id; }
}

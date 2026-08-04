using GameCore.Tag;

namespace GameCore.Graph;

/// <summary>
/// GraphNode — 统一图节点。种族进化图/职业转职图/任务图共用。
/// </summary>
public class GraphNode<T>
{
    public string Id { get; }
    public T Data { get; set; }
    public List<Edge<T>> OutgoingEdges { get; } = new();
    public List<Edge<T>> IncomingEdges { get; } = new();

    public GraphNode(string id, T data)
    {
        Id = id;
        Data = data;
    }

    public override string ToString() => Id;
}

/// <summary>
/// Edge — 完整边定义：标签条件 + 额外需求（等级/金币/道具）+ 可逆标记。
/// </summary>
public sealed class Edge<T>
{
    public GraphNode<T> From { get; internal set; }
    public GraphNode<T> To { get; internal set; }
    public ITagCondition Condition { get; init; }                    // 标签条件
    public Dictionary<string, object> Requirements { get; init; } = new(); // { level:10, gold:500, item:"龙鳞×3" }
    public bool Bidirectional { get; init; }                          // 职业图允许降级转回
    public string Description { get; init; }                          // UI 展示用

    public bool IsAvailable(IReadOnlySet<string> tagIds, EvalContext ctx = null)
    {
        if (Condition != null && !Condition.Evaluate(tagIds)) return false;
        return MeetsRequirements(ctx);
    }

    private bool MeetsRequirements(EvalContext ctx)
    {
        if (Requirements.Count == 0) return true;
        if (ctx == null) return false;
        if (Requirements.TryGetValue("level", out var level)
            && ctx.GetExtraInt("level") < Convert.ToInt32(level)) return false;
        if (Requirements.TryGetValue("gold", out var gold)
            && ctx.GetExtraInt("gold") < Convert.ToInt32(gold)) return false;
        // 道具检查、任务进度检查 → 委托给 ctx.Extra
        foreach (var kv in Requirements)
        {
            if (kv.Key is "level" or "gold") continue;
            var have = ctx.GetExtraInt(kv.Key);
            if (have < Convert.ToInt32(kv.Value)) return false;
        }
        return true;
    }
}

/// <summary>
/// GraphEngine — 统一图引擎：可用边查询 / 可遍历检查 / BFS最短路径 / 冲突检测。
/// </summary>
public sealed class GraphEngine<T>
{
    private readonly Dictionary<string, GraphNode<T>> _nodesById = new();
    private readonly List<GraphNode<T>> _allNodes = new();
    private readonly TagConditionParser _conditionParser;

    public GraphEngine(TagConditionParser parser)
    {
        _conditionParser = parser;
    }

    public TagConditionParser ConditionParser => _conditionParser;
    public IReadOnlyList<GraphNode<T>> AllNodes => _allNodes;

    public void AddNode(GraphNode<T> node)
    {
        _nodesById[node.Id] = node;
        _allNodes.Add(node);
    }

    public GraphNode<T> GetNode(string id)
        => _nodesById.TryGetValue(id, out var n) ? n : null;

    public Edge<T> Connect(string fromId, string toId, ITagCondition condition,
        Dictionary<string, object> requirements = null, bool bidirectional = false, string description = null)
    {
        var from = GetNode(fromId) ?? throw new ArgumentException($"未知节点: {fromId}");
        var to = GetNode(toId) ?? throw new ArgumentException($"未知节点: {toId}");
        var edge = new Edge<T>
        {
            From = from,
            To = to,
            Condition = condition,
            Requirements = requirements ?? new Dictionary<string, object>(),
            Bidirectional = bidirectional,
            Description = description
        };
        from.OutgoingEdges.Add(edge);
        to.IncomingEdges.Add(edge);
        if (bidirectional)
        {
            var reverse = new Edge<T>
            {
                From = to,
                To = from,
                Condition = condition,
                Requirements = edge.Requirements,
                Bidirectional = true,
                Description = description
            };
            to.OutgoingEdges.Add(reverse);
            from.IncomingEdges.Add(reverse);
        }
        return edge;
    }

    /// <summary>获得当前节点所有满足条件的出边。</summary>
    public List<Edge<T>> GetAvailableEdges(GraphNode<T> current, IReadOnlySet<string> tagIds, EvalContext ctx = null)
    {
        var result = new List<Edge<T>>();
        if (current == null) return result;
        foreach (var edge in current.OutgoingEdges)
        {
            if (edge.IsAvailable(tagIds, ctx)) result.Add(edge);
        }
        return result;
    }

    /// <summary>获得所有可达节点（从任意节点出发，满足条件的目标节点）。</summary>
    public List<GraphNode<T>> GetAvailableNodes(IReadOnlySet<string> tagIds, EvalContext ctx = null)
    {
        var result = new List<GraphNode<T>>();
        foreach (var node in _allNodes)
        {
            foreach (var edge in node.OutgoingEdges)
            {
                if (edge.IsAvailable(tagIds, ctx) && !result.Contains(edge.To))
                    result.Add(edge.To);
            }
        }
        return result;
    }

    /// <summary>检查特定边是否满足条件。</summary>
    public bool CanTraverse(string fromNodeId, string toNodeId, IReadOnlySet<string> tagIds, EvalContext ctx = null)
    {
        var from = GetNode(fromNodeId);
        if (from == null) return false;
        return from.OutgoingEdges.Any(e => e.To.Id == toNodeId && e.IsAvailable(tagIds, ctx));
    }

    /// <summary>BFS 最短路径（边序列）。</summary>
    public List<Edge<T>> ShortestPath(GraphNode<T> from, GraphNode<T> to)
    {
        if (from == null || to == null) return new List<Edge<T>>();
        var queue = new Queue<GraphNode<T>>();
        var cameFrom = new Dictionary<GraphNode<T>, Edge<T>>();
        var visited = new HashSet<GraphNode<T>> { from };
        queue.Enqueue(from);

        while (queue.Count > 0)
        {
            var current = queue.Dequeue();
            if (current == to) break;
            foreach (var edge in current.OutgoingEdges)
            {
                if (visited.Add(edge.To))
                {
                    cameFrom[edge.To] = edge;
                    queue.Enqueue(edge.To);
                }
            }
        }
        return ReconstructPath(cameFrom, to);
    }

    private static List<Edge<T>> ReconstructPath(Dictionary<GraphNode<T>, Edge<T>> cameFrom, GraphNode<T> to)
    {
        var path = new List<Edge<T>>();
        var current = to;
        while (cameFrom.TryGetValue(current, out var edge))
        {
            path.Insert(0, edge);
            current = edge.From;
        }
        return path;
    }

    /// <summary>获得从任意入口可达的所有节点（进化预览UI用）。</summary>
    public List<GraphNode<T>> GetAllReachableNodes(IReadOnlySet<string> tagIds, EvalContext ctx = null)
        => GetAvailableNodes(tagIds, ctx);

    /// <summary>冲突检测：新节点自带的 conflictTags 与当前标签集冲突时返回交集。</summary>
    public HashSet<string> DetectConflicts(GraphNode<T> newNode, IReadOnlySet<string> currentTagIds)
    {
        if (newNode.Data is RaceData rd)
        {
            var conflicts = new HashSet<string>(rd.ConflictTags);
            conflicts.IntersectWith(currentTagIds);
            return conflicts;
        }
        return new HashSet<string>();
    }
}

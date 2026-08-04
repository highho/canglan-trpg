using System.Text.Json;
using GameCore.Tag;

namespace GameCore.Graph;

/// <summary>
/// GraphLoader — 三图加载器。解析 JSON → 创建节点 → 解析边条件 → 连接。
/// </summary>
public sealed class GraphLoader
{
    private readonly TagConditionParser _parser;
    private readonly TagRegistry _registry;

    public GraphLoader(TagConditionParser parser, TagRegistry registry)
    {
        _parser = parser;
        _registry = registry;
    }

    // ==================== 种族进化图 ====================

    public GraphEngine<RaceData> LoadRaceGraph(string jsonPath)
        => LoadRaceGraphFromText(File.ReadAllText(jsonPath));

    public GraphEngine<RaceData> LoadRaceGraphFromText(string json)
    {
        var engine = new GraphEngine<RaceData>(_parser);
        using var doc = JsonDocument.Parse(json);

        // 第一遍：创建全部节点
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var node = prop.Value;
            var data = new RaceData(
                GetString(node, "name", prop.Name),
                ParseStringSet(node, "tagIds"),
                ParseFloatMap(node, "baseStats"),
                ParseStringSet(node, "conflictTags"));
            engine.AddNode(new RaceNode(prop.Name, data));
        }

        // 第二遍：连接边
        foreach (var prop in doc.RootElement.EnumerateObject())
            ConnectEdges(engine, prop.Name, prop.Value);

        return engine;
    }

    // ==================== 职业转职图 ====================

    public GraphEngine<ClassData> LoadClassGraph(string jsonPath)
        => LoadClassGraphFromText(File.ReadAllText(jsonPath));

    public GraphEngine<ClassData> LoadClassGraphFromText(string json)
    {
        var engine = new GraphEngine<ClassData>(_parser);
        using var doc = JsonDocument.Parse(json);

        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var node = prop.Value;
            var data = new ClassData(
                GetString(node, "name", prop.Name),
                ParseStringSet(node, "tagIds"),
                node.TryGetProperty("skillTreeRoot", out var st) ? st.GetString() : null,
                ParseFloatMap(node, "statGrowth"));
            engine.AddNode(new ClassNode(prop.Name, data));
        }

        foreach (var prop in doc.RootElement.EnumerateObject())
            ConnectEdges(engine, prop.Name, prop.Value);

        return engine;
    }

    // ==================== 任务图 ====================

    public GraphEngine<QuestData> LoadQuestGraph(string jsonPath)
        => LoadQuestGraphFromText(File.ReadAllText(jsonPath));

    public GraphEngine<QuestData> LoadQuestGraphFromText(string json)
    {
        var engine = new GraphEngine<QuestData>(_parser);
        using var doc = JsonDocument.Parse(json);

        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var node = prop.Value;
            ITagCondition accept = node.TryGetProperty("acceptCondition", out var ac) && ac.ValueKind == JsonValueKind.String
                ? _parser.Parse(ac.GetString())
                : null;
            var rewards = new Dictionary<string, int>();
            if (node.TryGetProperty("rewards", out var rw))
                foreach (var f in rw.EnumerateObject())
                    rewards[f.Name] = f.Value.ValueKind == JsonValueKind.Number ? f.Value.GetInt32() : 1;
            var data = new QuestData(
                GetString(node, "name", prop.Name),
                GetString(node, "description", ""),
                accept,
                node.TryGetProperty("minLevel", out var ml) ? ml.GetInt32() : 0,
                node.TryGetProperty("cooldown", out var cd) ? cd.GetInt32() : 0,
                ParseStringSet(node, "rewardTagIds"),
                rewards);
            engine.AddNode(new QuestNode(prop.Name, data));
        }

        foreach (var prop in doc.RootElement.EnumerateObject())
            ConnectEdges(engine, prop.Name, prop.Value);

        return engine;
    }

    // ==================== 公共 ====================

    private void ConnectEdges<T>(GraphEngine<T> engine, string fromId, JsonElement node)
    {
        if (!node.TryGetProperty("edges", out var edges)) return;
        foreach (var e in edges.EnumerateArray())
        {
            var to = e.GetProperty("to").GetString();
            ITagCondition condition = e.TryGetProperty("condition", out var c) && c.ValueKind == JsonValueKind.String
                ? _parser.Parse(c.GetString())
                : null;
            var requirements = new Dictionary<string, object>();
            if (e.TryGetProperty("requirements", out var req))
            {
                foreach (var f in req.EnumerateObject())
                    requirements[f.Name] = f.Value.ValueKind == JsonValueKind.Number ? (object)f.Value.GetInt32() : f.Value.GetString();
            }
            var bidirectional = e.TryGetProperty("bidirectional", out var bd) && bd.GetBoolean();
            var description = e.TryGetProperty("description", out var d) ? d.GetString() : null;
            engine.Connect(fromId, to, condition, requirements, bidirectional, description);
        }
    }

    private static string GetString(JsonElement node, string prop, string fallback)
        => node.TryGetProperty(prop, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : fallback;

    private static HashSet<string> ParseStringSet(JsonElement node, string prop)
    {
        var set = new HashSet<string>();
        if (!node.TryGetProperty(prop, out var arr) || arr.ValueKind != JsonValueKind.Array) return set;
        foreach (var item in arr.EnumerateArray()) set.Add(item.GetString());
        return set;
    }

    private static Dictionary<string, float> ParseFloatMap(JsonElement node, string prop)
    {
        var map = new Dictionary<string, float>();
        if (!node.TryGetProperty(prop, out var obj) || obj.ValueKind != JsonValueKind.Object) return map;
        foreach (var f in obj.EnumerateObject())
            map[f.Name] = (float)f.Value.GetDouble();
        return map;
    }
}

using System.Text.Json;
using GameCore.Effect;

namespace GameCore.Tag;

/// <summary>
/// 标签注册表：从 JSON 加载全部 TagDef。
/// 设计契约层的数据容器，数据加载时校验 allowedSources，运行时不动。
/// </summary>
public sealed class TagRegistry
{
    /// <summary>全局单例引用，供 TagTierAtLeast 等条件查询。</summary>
    public static TagRegistry Instance { get; private set; }

    /// <summary>[适性]标签的涌现效果：所有层级条件要求 -N 级。</summary>
    public int TierReduction { get; set; }

    private readonly Dictionary<string, TagDef> _defsById = new();
    private readonly Dictionary<TagCategory, List<TagDef>> _defsByCategory = new();

    public void Load(string jsonPath, EffectParser effectParser)
        => LoadFromText(File.ReadAllText(jsonPath), effectParser);

    public void LoadFromText(string json, EffectParser effectParser)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var id = prop.Name;
            var node = prop.Value;
            var def = new TagDef(
                id,
                node.TryGetProperty("name", out var n) ? n.GetString() : id,
                node.TryGetProperty("description", out var d) ? d.GetString() : "",
                ParseCategory(node),
                node.TryGetProperty("tier", out var t) ? t.GetInt32() : 1,
                ParseSources(node),
                effectParser.ParseEffects(node),
                ParseBehaviorWeights(node));
            Register(def);
        }
        Instance = this;
    }

    public void Register(TagDef def)
    {
        _defsById[def.Id] = def;
        if (!_defsByCategory.TryGetValue(def.Category, out var list))
            _defsByCategory[def.Category] = list = new List<TagDef>();
        list.Add(def);
    }

    public TagDef Get(string id)
    {
        if (!_defsById.TryGetValue(id, out var def))
            throw new ArgumentException($"未知标签: {id}");
        return def;
    }

    public bool TryGet(string id, out TagDef def) => _defsById.TryGetValue(id, out def);

    public IReadOnlyList<TagDef> GetByCategory(TagCategory category)
        => _defsByCategory.TryGetValue(category, out var list) ? list : Array.Empty<TagDef>();

    public IEnumerable<TagDef> GetAll() => _defsById.Values;

    private static TagCategory ParseCategory(JsonElement node)
    {
        var raw = node.TryGetProperty("category", out var c) ? c.GetString() : "ELEMENT";
        return raw.ToUpperInvariant() switch
        {
            "ELEMENT" => TagCategory.Element,
            "IDENTITY" => TagCategory.Identity,
            "PERSONALITY" => TagCategory.Personality,
            "EMOTION" => TagCategory.Emotion,
            "QUEST_MARK" => TagCategory.QuestMark,
            "SKILL" => TagCategory.Skill,
            _ => throw new ArgumentException($"未知标签分类: {raw}")
        };
    }

    private static IReadOnlySet<TagSource> ParseSources(JsonElement node)
    {
        var set = new HashSet<TagSource>();
        if (!node.TryGetProperty("allowedSources", out var arr)) return set;
        foreach (var s in arr.EnumerateArray())
        {
            set.Add(s.GetString().ToUpperInvariant() switch
            {
                "RACE" => TagSource.Race,
                "CLASS" => TagSource.Class,
                "QUEST" => TagSource.Quest,
                "TRAIT" => TagSource.Trait,
                "EQUIP" => TagSource.Equip,
                "BUFF" => TagSource.Buff,
                _ => throw new ArgumentException($"未知标签来源: {s.GetString()}")
            });
        }
        return set;
    }

    private static IReadOnlyDictionary<string, Dictionary<string, int>> ParseBehaviorWeights(JsonElement node)
    {
        var result = new Dictionary<string, Dictionary<string, int>>(StringComparer.OrdinalIgnoreCase);
        if (!node.TryGetProperty("behaviorWeights", out var bw)) return result;
        foreach (var pool in bw.EnumerateObject())
        {
            var dict = new Dictionary<string, int>();
            foreach (var opt in pool.Value.EnumerateArray())
                dict[opt.GetProperty("option").GetString()] = opt.GetProperty("weight").GetInt32();
            result[pool.Name] = dict;
        }
        return result;
    }
}

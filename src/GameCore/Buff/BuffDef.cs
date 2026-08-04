using System.Text.Json;
using GameCore.Effect;

namespace GameCore.Buff;

/// <summary>Buff 分类。</summary>
public enum BuffType
{
    /// <summary>永久Buff：装备穿戴期间生效，卸下时移除。</summary>
    Permanent,
    /// <summary>临时Buff：N回合后自然过期。</summary>
    Temporary,
    /// <summary>触发Buff：由标签TRIGGER效果创建，条件解除时自动移除。</summary>
    Triggered,
    /// <summary>场景Buff：进入场景时附加，离开时移除。</summary>
    Scene
}

/// <summary>BuffDef — Buff 配置定义（复用标签系统的 EffectDef 层次）。</summary>
public sealed record BuffDef(
    string Id,
    string Name,
    BuffType Type,
    int DefaultDuration,              // -1 = 永久
    IReadOnlyList<IEffectDef> Effects,
    bool Stackable,                   // 同名Buff是否可叠加
    int MaxStacks);                   // 最大叠加层数，1=不可叠加

/// <summary>
/// BuffRegistry — Buff 定义注册表（纯数据容器，从JSON加载）。
/// </summary>
public sealed class BuffRegistry
{
    public static BuffRegistry Instance { get; private set; }

    private readonly Dictionary<string, BuffDef> _defsById = new();

    public void Load(string jsonPath, EffectParser effectParser)
        => LoadFromText(File.ReadAllText(jsonPath), effectParser);

    public void LoadFromText(string json, EffectParser effectParser)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var id = prop.Name;
            var node = prop.Value;
            var def = new BuffDef(
                id,
                node.TryGetProperty("name", out var n) ? n.GetString() : id,
                ParseType(node),
                node.TryGetProperty("defaultDuration", out var d) ? d.GetInt32() : 3,
                effectParser.ParseEffects(node),
                node.TryGetProperty("stackable", out var s) && s.GetBoolean(),
                node.TryGetProperty("maxStacks", out var m) ? m.GetInt32() : 1);
            Register(def);
        }
        Instance = this;
    }

    public void Register(BuffDef def) => _defsById[def.Id] = def;

    public BuffDef Get(string id)
    {
        if (!_defsById.TryGetValue(id, out var def))
            throw new ArgumentException($"未知Buff: {id}");
        return def;
    }

    public bool TryGet(string id, out BuffDef def) => _defsById.TryGetValue(id, out def);

    public IEnumerable<BuffDef> GetAll() => _defsById.Values;

    private static BuffType ParseType(JsonElement node)
    {
        var raw = node.TryGetProperty("type", out var t) ? t.GetString() : "TEMPORARY";
        return raw.ToUpperInvariant() switch
        {
            "PERMANENT" => BuffType.Permanent,
            "TEMPORARY" => BuffType.Temporary,
            "TRIGGERED" => BuffType.Triggered,
            "SCENE" => BuffType.Scene,
            _ => throw new ArgumentException($"未知Buff类型: {raw}")
        };
    }
}

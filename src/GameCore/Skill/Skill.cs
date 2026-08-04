using System.Text.Json;
using GameCore.Effect;
using GameCore.Tag;

namespace GameCore.Skill;

/// <summary>技能类型。</summary>
public enum SkillType { Active, Passive, Ultimate }

/// <summary>目标模式（九宫格）。</summary>
public enum TargetPattern { Single, Row, Column, All, Self, Adjacent }

/// <summary>伤害类型。</summary>
public enum DamageType { Physical, Magical, True }

/// <summary>Skill — 战斗中的核心行动单元。</summary>
public sealed class Skill
{
    public string Id { get; }
    public string Name { get; }
    public SkillType Type { get; }
    public int Cooldown { get; }                 // 冷却回合数
    public int CurrentCooldown { get; set; }     // 当前剩余冷却
    public TargetPattern TargetPattern { get; }
    public IReadOnlyList<IEffectDef> Effects { get; }
    public ITagCondition UnlockCondition { get; }
    public int Range { get; }                    // 作用距离（格子数）
    public int BaseDamage { get; }
    public DamageType DamageType { get; }

    public Skill(string id, string name, SkillType type, int cooldown, TargetPattern targetPattern,
        IReadOnlyList<IEffectDef> effects, ITagCondition unlockCondition, int range,
        int baseDamage = 0, DamageType damageType = DamageType.Physical)
    {
        Id = id;
        Name = name;
        Type = type;
        Cooldown = cooldown;
        TargetPattern = targetPattern;
        Effects = effects;
        UnlockCondition = unlockCondition;
        Range = range;
        BaseDamage = baseDamage;
        DamageType = damageType;
    }

    public bool IsReady() => CurrentCooldown == 0;

    public void TickCooldown()
    {
        if (CurrentCooldown > 0) CurrentCooldown--;
    }

    public void Use() => CurrentCooldown = Cooldown;

    public override string ToString() => $"{Name}({Id}) CD:{CurrentCooldown}/{Cooldown}";
}

/// <summary>技能注册表（skills.json）。</summary>
public sealed class SkillRegistry
{
    public static SkillRegistry Instance { get; private set; }

    private readonly Dictionary<string, Skill> _skillsById = new();

    public void Load(string jsonPath, EffectParser effectParser, TagConditionParser conditionParser)
        => LoadFromText(File.ReadAllText(jsonPath), effectParser, conditionParser);

    public void LoadFromText(string json, EffectParser effectParser, TagConditionParser conditionParser)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var id = prop.Name;
            var node = prop.Value;
            ITagCondition unlock = node.TryGetProperty("unlockCondition", out var uc) && uc.ValueKind == JsonValueKind.String
                ? conditionParser.Parse(uc.GetString())
                : null;
            var skill = new Skill(
                id,
                node.TryGetProperty("name", out var n) ? n.GetString() : id,
                ParseType(node),
                node.TryGetProperty("cooldown", out var cd) ? cd.GetInt32() : 0,
                ParsePattern(node),
                effectParser.ParseEffects(node),
                unlock,
                node.TryGetProperty("range", out var r) ? r.GetInt32() : 1,
                node.TryGetProperty("baseDamage", out var bd) ? bd.GetInt32() : 0,
                ParseDamageType(node));
            Register(skill);
        }
        Instance = this;
    }

    public void Register(Skill skill) => _skillsById[skill.Id] = skill;

    public Skill Get(string id)
        => _skillsById.TryGetValue(id, out var s) ? s : throw new ArgumentException($"未知技能: {id}");

    public bool TryGet(string id, out Skill skill) => _skillsById.TryGetValue(id, out skill);

    public IEnumerable<Skill> GetAll() => _skillsById.Values;

    private static SkillType ParseType(JsonElement node)
    {
        var raw = node.TryGetProperty("type", out var t) ? t.GetString() : "ACTIVE";
        return raw.ToUpperInvariant() switch
        {
            "ACTIVE" => SkillType.Active,
            "PASSIVE" => SkillType.Passive,
            "ULTIMATE" => SkillType.Ultimate,
            _ => throw new ArgumentException($"未知技能类型: {raw}")
        };
    }

    private static TargetPattern ParsePattern(JsonElement node)
    {
        var raw = node.TryGetProperty("targetPattern", out var t) ? t.GetString() : "SINGLE";
        return raw.ToUpperInvariant() switch
        {
            "SINGLE" => TargetPattern.Single,
            "ROW" => TargetPattern.Row,
            "COLUMN" => TargetPattern.Column,
            "ALL" => TargetPattern.All,
            "SELF" => TargetPattern.Self,
            "ADJACENT" => TargetPattern.Adjacent,
            _ => throw new ArgumentException($"未知目标模式: {raw}")
        };
    }

    private static DamageType ParseDamageType(JsonElement node)
    {
        var raw = node.TryGetProperty("damageType", out var t) ? t.GetString() : "PHYSICAL";
        return raw.ToUpperInvariant() switch
        {
            "PHYSICAL" => DamageType.Physical,
            "MAGICAL" => DamageType.Magical,
            "TRUE" => DamageType.True,
            _ => DamageType.Physical
        };
    }
}

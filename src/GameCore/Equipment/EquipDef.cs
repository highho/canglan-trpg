using System.Text.Json;
using GameCore.Effect;
using GameCore.Tag;

namespace GameCore.Equipment;

/// <summary>装备槽位。</summary>
public enum EquipSlot { Weapon, Armor, Accessory, Ring1, Ring2 }

/// <summary>EquipDef — 装备配置定义（equipments.json）。</summary>
public sealed record EquipDef(
    string Id,                                // "shadow_blade"
    string Name,                              // "暗影之刃"
    EquipSlot Slot,                           // WEAPON / ARMOR / ACCESSORY / RING
    int Tier,                                 // 品质等级 1-5
    IReadOnlyDictionary<string, float> BaseStats,   // 基础属性加成: { ATK: 18, CRIT: 0.15 }
    IReadOnlyList<IEffectDef> Effects,        // 复用标签系统的 EffectDef 层次
    ITagCondition EquipCondition,             // 穿戴条件（条件评估接口）
    int MaxDurability,                        // 最大耐久度
    string SetId,                             // 套装ID，null=非套装
    string UpgradePath);                      // 升级目标装备ID，null=不可升级

/// <summary>Equip — 运行时装备实例。</summary>
public sealed class Equip
{
    public string Id { get; }
    public string Name { get; }
    public EquipSlot Slot { get; }
    public int Tier { get; }
    public IReadOnlyDictionary<string, float> BaseStats { get; }
    public IReadOnlyList<IEffectDef> Effects { get; }
    public ITagCondition EquipCondition { get; }
    public int MaxDurability { get; }
    public int CurrentDurability { get; set; }
    public string SetId { get; }
    public string UpgradePath { get; }

    public Equip(EquipDef def)
    {
        Id = def.Id;
        Name = def.Name;
        Slot = def.Slot;
        Tier = def.Tier;
        BaseStats = def.BaseStats;
        Effects = def.Effects;
        EquipCondition = def.EquipCondition;
        MaxDurability = def.MaxDurability;
        CurrentDurability = def.MaxDurability;
        SetId = def.SetId;
        UpgradePath = def.UpgradePath;
    }

    public bool IsBroken() => CurrentDurability <= 0;
    public bool IsDamaged() => CurrentDurability < MaxDurability * 0.3;
    public bool CanUpgrade() => UpgradePath != null;

    public void ConsumeDurability(int amount) => CurrentDurability = Math.Max(0, CurrentDurability - amount);
    public void Repair(int amount) => CurrentDurability = Math.Min(MaxDurability, CurrentDurability + amount);

    public override string ToString() => $"{Name}({Id}) 耐久{CurrentDurability}/{MaxDurability}";
}

/// <summary>装备注册表（从 equipments.json 加载）。</summary>
public sealed class EquipRegistry
{
    public static EquipRegistry Instance { get; private set; }

    private readonly Dictionary<string, EquipDef> _defsById = new();

    public void Load(string jsonPath, EffectParser effectParser, TagConditionParser conditionParser)
        => LoadFromText(File.ReadAllText(jsonPath), effectParser, conditionParser);

    public void LoadFromText(string json, EffectParser effectParser, TagConditionParser conditionParser)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var prop in doc.RootElement.EnumerateObject())
        {
            var id = prop.Name;
            var node = prop.Value;
            ITagCondition condition = node.TryGetProperty("equipCondition", out var ec) && ec.ValueKind == JsonValueKind.String
                ? conditionParser.Parse(ec.GetString())
                : null;
            var baseStats = new Dictionary<string, float>();
            if (node.TryGetProperty("baseStats", out var bs))
                foreach (var f in bs.EnumerateObject())
                    baseStats[f.Name] = (float)f.Value.GetDouble();
            var def = new EquipDef(
                id,
                node.TryGetProperty("name", out var n) ? n.GetString() : id,
                ParseSlot(node),
                node.TryGetProperty("tier", out var t) ? t.GetInt32() : 1,
                baseStats,
                effectParser.ParseEffects(node),
                condition,
                node.TryGetProperty("maxDurability", out var md) ? md.GetInt32() : 50,
                node.TryGetProperty("setId", out var s) ? s.GetString() : null,
                node.TryGetProperty("upgradePath", out var u) ? u.GetString() : null);
            Register(def);
        }
        Instance = this;
    }

    public void Register(EquipDef def) => _defsById[def.Id] = def;

    public EquipDef Get(string id)
        => _defsById.TryGetValue(id, out var def) ? def : throw new ArgumentException($"未知装备: {id}");

    public bool TryGet(string id, out EquipDef def) => _defsById.TryGetValue(id, out def);

    public IEnumerable<EquipDef> GetAll() => _defsById.Values;

    public static EquipSlot ParseSlot(string raw) => raw.ToUpperInvariant() switch
    {
        "WEAPON" => EquipSlot.Weapon,
        "ARMOR" => EquipSlot.Armor,
        "ACCESSORY" => EquipSlot.Accessory,
        "RING1" => EquipSlot.Ring1,
        "RING2" => EquipSlot.Ring2,
        _ => throw new ArgumentException($"未知槽位: {raw}")
    };

    private static EquipSlot ParseSlot(JsonElement node)
        => ParseSlot(node.TryGetProperty("slot", out var s) ? s.GetString() : "WEAPON");
}

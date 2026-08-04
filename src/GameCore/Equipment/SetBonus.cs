using System.Text.Json;
using GameCore.Effect;

namespace GameCore.Equipment;

/// <summary>SetBonusDef — 套装效果定义（N件同套装触发）。</summary>
public sealed record SetBonusDef(
    string SetId,                 // "shadow_set"
    int PiecesRequired,           // 2 / 3 / 4 / 5
    string BonusName,             // "暗影之力"
    IReadOnlyList<IEffectDef> BonusEffects);

/// <summary>
/// SetBonusRegistry — 套装效果注册表。
/// Get(setId, count) 返回 ≤ count 的最大件数效果。
/// </summary>
public sealed class SetBonusRegistry
{
    public static SetBonusRegistry Instance { get; private set; }

    private readonly Dictionary<string, Dictionary<int, SetBonusDef>> _bonusesById = new();

    public void Load(string jsonPath, EffectParser effectParser)
        => LoadFromText(File.ReadAllText(jsonPath), effectParser);

    public void LoadFromText(string json, EffectParser effectParser)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var setProp in doc.RootElement.EnumerateObject())
        {
            var setId = setProp.Name;
            if (!setProp.Value.TryGetProperty("bonuses", out var bonuses)) continue;
            var tierMap = new Dictionary<int, SetBonusDef>();
            foreach (var bonusProp in bonuses.EnumerateObject())
            {
                var pieces = int.Parse(bonusProp.Name);
                var def = new SetBonusDef(
                    setId,
                    pieces,
                    bonusProp.Value.TryGetProperty("name", out var n) ? n.GetString() : $"{setId}_{pieces}",
                    effectParser.ParseEffects(bonusProp.Value));
                tierMap[pieces] = def;
            }
            _bonusesById[setId] = tierMap;
        }
        Instance = this;
    }

    public void Register(SetBonusDef def)
    {
        if (!_bonusesById.TryGetValue(def.SetId, out var tierMap))
            _bonusesById[def.SetId] = tierMap = new Dictionary<int, SetBonusDef>();
        tierMap[def.PiecesRequired] = def;
    }

    /// <summary>查找特定套装N件时的效果：返回 ≤ count 的最大件数档位。</summary>
    public SetBonusDef Get(string setId, int count)
    {
        if (!_bonusesById.TryGetValue(setId, out var tierMap)) return null;
        SetBonusDef best = null;
        foreach (var kv in tierMap)
        {
            if (kv.Key <= count && (best == null || kv.Key > best.PiecesRequired))
                best = kv.Value;
        }
        return best;
    }
}

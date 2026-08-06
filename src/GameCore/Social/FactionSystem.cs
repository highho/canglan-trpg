namespace GameCore.Social;

/// <summary>阵营枚举：游骑兵/冒险公会/商盟/暗影/圣殿/平民。</summary>
public enum FactionId
{
    Ranger,         // 游骑兵：狩猎/森林/驯兽任务
    Adventurer,     // 冒险者公会：讨伐/护送/探索
    Merchant,       // 商盟：运输/矿石/制造
    Shadow,         // 暗影：潜行/暗杀/盗窃
    Holy,           // 圣殿：治疗/驱魔/誓约
    Citizen,        // 平民声望：救助/说服/日常
}

/// <summary>阵营声望等级。</summary>
public enum RepLevel { Hostile = -1, Neutral = 0, Friendly = 1, Honored = 2, Revered = 3 }

/// <summary>阵营声望管理。单例在 GameWorld.Bootstrap 中初始化。</summary>
public sealed class FactionSystem
{
    public static FactionSystem Instance { get; private set; }

    private readonly Dictionary<FactionId, int> _rep = new();

    public FactionSystem()
    {
        Instance = this;
        foreach (FactionId f in Enum.GetValues<FactionId>())
            _rep[f] = 0;   // 初始中立
    }

    /// <summary>获取声望值。</summary>
    public int Get(FactionId fid) => _rep.GetValueOrDefault(fid, 0);

    /// <summary>从 reward 键 "reputation_xxx" 中加声望（xxx=ranger/adventurer/merchant/shadow/holy/citizen）。</summary>
    public void Add(string rewardKey, int amount)
    {
        var fid = ParseFaction(rewardKey);
        if (fid == null) return;
        Add(fid.Value, amount);
    }

    public void Add(FactionId fid, int amount)
    {
        _rep[fid] = Math.Clamp(_rep.GetValueOrDefault(fid) + amount, -999, 999);
    }

    /// <summary>获取声望等级。</summary>
    public RepLevel GetLevel(FactionId fid)
    {
        var v = Get(fid);
        if (v < -50) return RepLevel.Hostile;
        if (v < 100) return RepLevel.Neutral;
        if (v < 300) return RepLevel.Friendly;
        if (v < 600) return RepLevel.Honored;
        return RepLevel.Revered;
    }

    /// <summary>获取对应[声望Lv]标签ID（用于 VM/Condition 检查）。</summary>
    public string GetTagId(FactionId fid) => GetLevel(fid) switch
    {
        RepLevel.Hostile => $"{fid}声望敌视",
        RepLevel.Friendly => $"{fid}声望友善",
        RepLevel.Honored => $"{fid}声望尊敬",
        RepLevel.Revered => $"{fid}声望崇敬",
        _ => $"{fid}声望中立"
    };

    /// <summary>初始化/读档恢复。</summary>
    public void Restore(Dictionary<FactionId, int> saved)
    {
        foreach (var (k, v) in saved)
            _rep[k] = v;
    }

    public Dictionary<FactionId, int> Snapshot() => new(_rep);

    private static FactionId? ParseFaction(string key)
    {
        if (string.IsNullOrEmpty(key)) return null;
        if (key.StartsWith("reputation_")) key = key["reputation_".Length..];
        return key.ToLowerInvariant() switch
        {
            "ranger" => FactionId.Ranger,
            "adventurer" => FactionId.Adventurer,
            "merchant" => FactionId.Merchant,
            "shadow" => FactionId.Shadow,
            "holy" => FactionId.Holy,
            "citizen" => FactionId.Citizen,
            _ => null
        };
    }
}

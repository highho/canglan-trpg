using System.Text.Json;
using GameCore.Unit;

namespace GameCore.Social;

// ==================== 好感度 ====================

/// <summary>好感度事件（预设：完成任务+15 / 保护+30 / 偷窃-60 / 背叛-100）。</summary>
public sealed record AffinityEvent(string EventType, int BaseValue)
{
    public static readonly AffinityEvent QuestComplete = new("QUEST_COMPLETE", 15);
    public static readonly AffinityEvent BattleProtect = new("BATTLE_PROTECT", 30);
    public static readonly AffinityEvent StealFrom = new("STEAL", -60);
    public static readonly AffinityEvent Betray = new("BETRAY", -100);
    public static AffinityEvent Dialogue(int value) => new("DIALOGUE", value);
}

/// <summary>
/// TagAffinityMatrix — 标签亲和矩阵（tag_affinity.json）。
/// { "贵族": { "趋炎附势": +20 }, "叛徒": { "正义": -40 } }
/// </summary>
public sealed class TagAffinityMatrix
{
    public static TagAffinityMatrix Instance { get; private set; } = new();

    private readonly Dictionary<string, Dictionary<string, int>> _matrix = new();

    public void Load(string jsonPath) => LoadFromText(File.ReadAllText(jsonPath));

    public void LoadFromText(string json)
    {
        using var doc = JsonDocument.Parse(json);
        foreach (var a in doc.RootElement.EnumerateObject())
        {
            var row = new Dictionary<string, int>();
            foreach (var b in a.Value.EnumerateObject())
                row[b.Name] = b.Value.GetInt32();
            _matrix[a.Name] = row;
        }
        Instance = this;
    }

    public void Set(string tagA, string tagB, int modifier)
    {
        if (!_matrix.TryGetValue(tagA, out var row)) _matrix[tagA] = row = new Dictionary<string, int>();
        row[tagB] = modifier;
    }

    public int GetModifier(string tagA, string tagB)
        => _matrix.TryGetValue(tagA, out var row) && row.TryGetValue(tagB, out var v) ? v : 0;
}

/// <summary>
/// AffinitySystem — 好感度计算引擎。
/// 初始 = 标签匹配修正（[-50,50]）；事件修正后 [-100,100]。
/// </summary>
public static class AffinitySystem
{
    /// <summary>初始化好感度：基础值 + 标签匹配修正。</summary>
    public static int InitAffinity(Unit.Unit player, Unit.Unit npc)
    {
        var value = 0;
        foreach (var pTag in player.ActiveTagIds)
            foreach (var nTag in npc.ActiveTagIds)
                value += TagAffinityMatrix.Instance.GetModifier(pTag, nTag);
        return Math.Clamp(value, -50, 50);
    }

    /// <summary>事件修正后更新。</summary>
    public static int ApplyEvent(int current, AffinityEvent evt)
        => Math.Clamp(current + evt.BaseValue, -100, 100);
}

// ==================== 声望 ====================

/// <summary>声望等级（含最低声望与折扣）。</summary>
public enum Rank
{
    Neutral = 0,
    Bronze = 1,
    Silver = 2,
    Gold = 3,
    Legend = 4
}

/// <summary>声望等级元数据。</summary>
public static class RankInfo
{
    public static int MinReputation(Rank rank) => rank switch
    {
        Rank.Bronze => 100,
        Rank.Silver => 300,
        Rank.Gold => 1000,
        Rank.Legend => 3000,
        _ => 0
    };

    /// <summary>声望折扣：铜5% / 银10% / 金20% / 传说30%。</summary>
    public static float Discount(Rank rank) => rank switch
    {
        Rank.Bronze => 0.05f,
        Rank.Silver => 0.10f,
        Rank.Gold => 0.20f,
        Rank.Legend => 0.30f,
        _ => 0f
    };

    public static Rank FromReputation(int rep)
    {
        var result = Rank.Neutral;
        foreach (Rank r in Enum.GetValues<Rank>())
            if (rep >= MinReputation(r)) result = r;
        return result;
    }

    public static Rank FromName(string name) => name?.ToUpperInvariant() switch
    {
        "BRONZE" or "铜" => Rank.Bronze,
        "SILVER" or "银" => Rank.Silver,
        "GOLD" or "金" => Rank.Gold,
        "LEGEND" or "传说" => Rank.Legend,
        _ => Rank.Neutral
    };
}

/// <summary>
/// ReputationSystem — 声望系统。factionId → unitId → 声望值。
/// </summary>
public sealed class ReputationSystem
{
    private readonly Dictionary<string, Dictionary<string, int>> _factionReps = new();

    public int Get(string factionId, string unitId)
        => _factionReps.TryGetValue(factionId, out var map)
           && map.TryGetValue(unitId, out var v) ? v : 0;

    public void Adjust(string factionId, string unitId, int delta)
    {
        if (!_factionReps.TryGetValue(factionId, out var map))
            _factionReps[factionId] = map = new Dictionary<string, int>();
        map[unitId] = Get(factionId, unitId) + delta;
    }

    public Rank GetRank(string factionId, string unitId)
        => RankInfo.FromReputation(Get(factionId, unitId));

    /// <summary>声望折扣。</summary>
    public float GetDiscount(string factionId, string unitId)
        => RankInfo.Discount(GetRank(factionId, unitId));

    /// <summary>声望级别比较（&gt;=0 表示达到 rankName）。</summary>
    public int CompareRank(int reputation, string rankName)
        => ((int)RankInfo.FromReputation(reputation)).CompareTo((int)RankInfo.FromName(rankName));

    /// <summary>存档导出：factionId → { unitId → rep }。</summary>
    public Dictionary<string, Dictionary<string, int>> ToSaveMap()
        => _factionReps.ToDictionary(kv => kv.Key, kv => new Dictionary<string, int>(kv.Value));

    public void LoadFrom(Dictionary<string, Dictionary<string, int>> map)
    {
        _factionReps.Clear();
        if (map == null) return;
        foreach (var kv in map) _factionReps[kv.Key] = new Dictionary<string, int>(kv.Value);
    }
}

// ==================== 交易 ====================

/// <summary>商店商品条目。</summary>
public sealed record ShopItem(string ItemId, int Quantity, int BasePrice);

/// <summary>交易结果。</summary>
public sealed record TransactionResult(bool Success, int TotalCost, string Error)
{
    public static TransactionResult Ok(int cost) => new(true, cost, null);
    public static TransactionResult Fail(string err) => new(false, 0, err);
}

/// <summary>
/// Shop — 商店。势力归属 + 声望门槛 + 库存 + 刷新冷却（12回合）。
/// </summary>
public sealed class Shop
{
    public string FactionId { get; }
    public Rank MinRank { get; }

    private readonly Dictionary<string, ShopItem> _stock = new();
    private int _refreshCooldown;

    public Shop(string factionId, Rank minRank = Rank.Neutral)
    {
        FactionId = factionId;
        MinRank = minRank;
    }

    public bool CanEnter(Unit.Unit player, ReputationSystem rep)
        => (int)rep.GetRank(FactionId, player.Id) >= (int)MinRank;

    public void SetStock(string itemId, int quantity, int basePrice)
        => _stock[itemId] = new ShopItem(itemId, quantity, basePrice);

    public ShopItem GetStock(string itemId) => _stock.TryGetValue(itemId, out var s) ? s : null;

    public bool HasStock(string itemId, int quantity)
        => _stock.TryGetValue(itemId, out var s) && s.Quantity >= quantity;

    public void RemoveStock(string itemId, int quantity)
    {
        if (_stock.TryGetValue(itemId, out var s))
            _stock[itemId] = s with { Quantity = s.Quantity - quantity };
    }

    /// <summary>刷新库存（12回合冷却）。</summary>
    public void Refresh(int turn, IEnumerable<ShopItem> newStock)
    {
        if (_refreshCooldown > turn) return;
        _stock.Clear();
        foreach (var item in newStock) _stock[item.ItemId] = item;
        _refreshCooldown = turn + 12;
    }

    public IReadOnlyList<ShopItem> GetAllStock() => _stock.Values.ToList();
}

/// <summary>
/// TradeSystem — 交易系统。价格 = 基础价 × (1-声望折扣) × 标签修正。
/// 标签修正：商人-10% / 贵族-15% / 通缉犯+50% / 穷困+20%。
/// </summary>
public sealed class TradeSystem
{
    private readonly ReputationSystem _reputation;

    public TradeSystem(ReputationSystem reputation)
    {
        _reputation = reputation;
    }

    /// <summary>计算最终价格。</summary>
    public int CalculatePrice(int basePrice, Unit.Unit buyer, Shop shop)
    {
        var price = (float)basePrice;
        price *= 1 - _reputation.GetDiscount(shop.FactionId, buyer.Id);
        price *= GetTagPriceModifier(buyer);
        return Math.Max(1, (int)price);
    }

    private static float GetTagPriceModifier(Unit.Unit buyer)
    {
        var mod = 1.0f;
        if (buyer.HasTag("商人")) mod *= 0.90f;
        if (buyer.HasTag("贵族")) mod *= 0.85f;
        if (buyer.HasTag("通缉犯")) mod *= 1.50f;
        if (buyer.HasTag("穷困")) mod *= 1.20f;
        return mod;
    }

    /// <summary>执行购买：金币检查 → 库存检查 → 扣款/扣库存/入背包。</summary>
    public TransactionResult Buy(Unit.Unit buyer, string itemId, int quantity, Shop shop)
    {
        var stock = shop.GetStock(itemId);
        if (stock == null) return TransactionResult.Fail("无此商品");
        var price = CalculatePrice(stock.BasePrice, buyer, shop) * quantity;
        if (buyer.Gold < price) return TransactionResult.Fail("金币不足");
        if (!shop.HasStock(itemId, quantity)) return TransactionResult.Fail("库存不足");

        buyer.Gold -= price;
        shop.RemoveStock(itemId, quantity);
        buyer.Inventory.Add(itemId, quantity);
        return TransactionResult.Ok(price);
    }

    /// <summary>出售：物品按基础价×50%回收。</summary>
    public TransactionResult Sell(Unit.Unit seller, string itemId, int quantity)
    {
        if (!seller.Inventory.HasItem(itemId) || seller.Inventory.Count(itemId) < quantity)
            return TransactionResult.Fail("物品不足");
        var def = Item.ItemRegistry.Instance?.TryGet(itemId, out var d) == true ? d : null;
        var price = Math.Max(1, (def?.Value ?? 1) / 2) * quantity;
        seller.Inventory.Remove(itemId, quantity);
        seller.Gold += price;
        return TransactionResult.Ok(price);
    }
}

// ==================== 冒险者公会 ====================

/// <summary>
/// AdventureGuild — 冒险者公会：任务板（标签+声望推荐）/ 训练场（声望折扣）。
/// </summary>
public sealed class AdventureGuild
{
    public const string GuildId = "adventurer_guild";

    private readonly Graph.GraphEngine<Graph.QuestData> _questGraph;
    private readonly ReputationSystem _reputation;
    private readonly Dictionary<string, Rank> _questMinRanks = new();   // questId → 最低声望等级

    public AdventureGuild(Graph.GraphEngine<Graph.QuestData> questGraph, ReputationSystem reputation)
    {
        _questGraph = questGraph;
        _reputation = reputation;
    }

    /// <summary>设置任务声望门槛。</summary>
    public void SetQuestMinRank(string questId, Rank rank) => _questMinRanks[questId] = rank;

    /// <summary>根据玩家标签+等级+声望推荐可用任务。</summary>
    public List<Graph.QuestNode> GetAvailableQuests(Unit.Unit player)
        => _questGraph.AllNodes.OfType<Graph.QuestNode>()
            .Where(n => n.CanAccept(player.ActiveTagIds, player.Level))
            .Where(n => MeetsReputation(n.Id, player))
            .ToList();

    private bool MeetsReputation(string questId, Unit.Unit player)
    {
        if (!_questMinRanks.TryGetValue(questId, out var minRank)) return true;
        var rep = _reputation.Get(GuildId, player.Id);
        return _reputation.CompareRank(rep, minRank.ToString()) >= 0;
    }

    /// <summary>训练场：消耗金币（声望折扣）训练技能，返回是否成功。</summary>
    public bool TrainSkill(Unit.Unit player, string skillId, int goldCost, Action<string> unlockAction = null)
    {
        var discount = _reputation.GetDiscount(GuildId, player.Id);
        var actualCost = (int)(goldCost * (1 - discount));
        if (player.Gold < actualCost) return false;
        player.Gold -= actualCost;
        unlockAction?.Invoke(skillId);
        return true;
    }
}

using GameCore.Effect;
using GameCore.EventBus;
using GameCore.Unit;

namespace GameCore.Ally;

/// <summary>招募类型：感情招募 / 雇佣。</summary>
public enum RecruitmentType { Bond, Mercenary }

/// <summary>招募结果。</summary>
public sealed record RecruitmentResult(bool Success, string Message)
{
    public static RecruitmentResult Ok(string msg) => new(true, msg);
    public static RecruitmentResult Fail(string msg) => new(false, msg);
}

/// <summary>招募定义（recruitment.json / NPC元数据）。</summary>
public sealed record RecruitmentDef(
    Tag.ITagCondition BondCondition,    // 感情招募标签条件
    int MinAffinity,                    // 最低好感度（默认60）
    bool AllowHire,                     // 是否接受雇佣
    int HireCost,                       // 雇佣金币
    int ContractDuration);              // 合约期（回合数）

/// <summary>
/// HireContract — 雇佣合约：倒计时 + 续约。
/// </summary>
public sealed class HireContract
{
    public int Cost { get; }
    public int TotalDuration { get; }
    public int RemainingDuration { get; private set; }
    public bool Expired { get; private set; }

    public HireContract(int cost, int duration)
    {
        Cost = cost;
        TotalDuration = duration;
        RemainingDuration = duration;
    }

    public void Tick()
    {
        if (Expired) return;
        RemainingDuration--;
        if (RemainingDuration <= 0) Expired = true;
    }

    /// <summary>续约。</summary>
    public void Renew()
    {
        RemainingDuration = TotalDuration;
        Expired = false;
    }

    public int RenewalCost => Cost;
}

/// <summary>
/// RecruitmentSystem — 招募系统。感情招募 = 标签条件 + 好感度；雇佣 = 金币 + 合约。
/// 招募定义存于 target.Metadata["recruitmentDef"]。
/// </summary>
public static class RecruitmentSystem
{
    public static RecruitmentDef GetRecruitmentDef(Unit.Unit target)
        => target.Metadata.TryGetValue("recruitmentDef", out var raw) ? raw as RecruitmentDef : null;

    /// <summary>感情招募：标签条件 + 好感度检查 → 成功转为队友。</summary>
    public static RecruitmentResult RecruitByBond(Unit.Unit player, Unit.Unit target)
    {
        var def = GetRecruitmentDef(target);
        if (def == null) return RecruitmentResult.Fail("该角色无法招募");

        if (!def.BondCondition.Evaluate(player.ActiveTagIds))
            return RecruitmentResult.Fail("不满足标签条件");

        var affinity = target.Affinity;
        if (affinity < def.MinAffinity)
            return RecruitmentResult.Fail($"好感度不足: {affinity}/{def.MinAffinity}");

        target.Role = UnitRole.Ally;
        target.IsMercenary = false;
        target.Metadata["recruitmentType"] = RecruitmentType.Bond;
        return RecruitmentResult.Ok($"成功招募 {target.Name}");
    }

    /// <summary>雇佣：金币 + 合约条件 → 限时队友。</summary>
    public static RecruitmentResult Hire(Unit.Unit player, Unit.Unit target)
    {
        var def = GetRecruitmentDef(target);
        if (def == null || !def.AllowHire)
            return RecruitmentResult.Fail("该角色不接受雇佣");

        if (player.Gold < def.HireCost)
            return RecruitmentResult.Fail($"金币不足: {player.Gold}/{def.HireCost}");

        player.Gold -= def.HireCost;
        target.Role = UnitRole.Ally;
        target.IsMercenary = true;
        target.HireCost = def.HireCost;
        target.ContractDuration = def.ContractDuration;
        target.Metadata["recruitmentType"] = RecruitmentType.Mercenary;
        target.Metadata["hireContract"] = new HireContract(def.HireCost, def.ContractDuration);
        return RecruitmentResult.Ok($"雇佣成功，合约期 {def.ContractDuration} 回合");
    }
}

/// <summary>
/// MercenaryManager — 雇佣兵合约管理。TURN_END 倒计时 → 到期发射 CONTRACT_EXPIRED。
/// 订阅属主 = 本实例。
/// </summary>
public sealed class MercenaryManager
{
    private readonly IEventBus _eventBus;
    private readonly List<Unit.Unit> _mercenaries = new();

    public MercenaryManager(IEventBus bus)
    {
        _eventBus = bus;
        bus.SubscribeWithOwner(EventTypes.TurnEnd, _ => TickAll(), this);
    }

    public void Register(Unit.Unit mercenary)
    {
        if (mercenary.IsMercenary && !_mercenaries.Contains(mercenary))
            _mercenaries.Add(mercenary);
    }

    private void TickAll()
    {
        var toNotify = new List<Unit.Unit>();
        foreach (var m in _mercenaries)
        {
            if (m.Metadata.TryGetValue("hireContract", out var raw) && raw is HireContract contract)
            {
                contract.Tick();
                if (contract.Expired) toNotify.Add(m);
            }
        }
        foreach (var m in toNotify)
            _eventBus.Emit(EventTypes.ContractExpired, m);   // → UI提示续约或解散
    }

    /// <summary>雇佣兵转正：到期时感情条件满足 → 永久队友。</summary>
    public bool PromoteToCompanion(Unit.Unit mercenary, Unit.Unit player)
    {
        if (!mercenary.IsMercenary) return false;
        if (mercenary.Metadata.TryGetValue("hireContract", out var raw) && raw is HireContract contract
            && !contract.Expired) return false;

        var def = RecruitmentSystem.GetRecruitmentDef(mercenary);
        if (def == null) return false;
        if (!def.BondCondition.Evaluate(player.ActiveTagIds)) return false;
        if (mercenary.Affinity < def.MinAffinity) return false;

        mercenary.IsMercenary = false;
        mercenary.Metadata.Remove("hireContract");
        mercenary.Metadata["recruitmentType"] = RecruitmentType.Bond;
        _mercenaries.Remove(mercenary);
        return true;
    }

    /// <summary>续约（扣玩家金币）。</summary>
    public bool Renew(Unit.Unit mercenary, Unit.Unit player)
    {
        if (mercenary.Metadata.TryGetValue("hireContract", out var raw) && raw is HireContract contract)
        {
            if (player.Gold < contract.RenewalCost) return false;
            player.Gold -= contract.RenewalCost;
            contract.Renew();
            return true;
        }
        return false;
    }
}

// ==================== 羁绊 ====================

/// <summary>羁绊等级（好感度映射）。</summary>
public enum BondLevel { Stranger, Acquaintance, Friend, Close, Soulbond }

/// <summary>羁绊技能定义：玩家条件 × 队友条件 × 最低羁绊等级 → 技能ID。</summary>
public sealed record BondSkillDef(
    Tag.ITagCondition PlayerCondition,
    Tag.ITagCondition CompanionCondition,
    BondLevel MinLevel,
    string SkillId);

/// <summary>羁绊技能注册表（bond_skills.json）。</summary>
public sealed class BondSkillRegistry
{
    public static BondSkillRegistry Instance { get; private set; } = new();

    private readonly List<BondSkillDef> _defs = new();

    public void Register(BondSkillDef def) => _defs.Add(def);

    public void LoadFromText(string json)
    {
        var parser = new Tag.TagConditionParser();
        using var doc = System.Text.Json.JsonDocument.Parse(json);
        foreach (var e in doc.RootElement.EnumerateArray())
        {
            Register(new BondSkillDef(
                e.TryGetProperty("playerCondition", out var pc) ? parser.Parse(pc.GetString()) : new Tag.AlwaysTrue(),
                e.TryGetProperty("companionCondition", out var cc) ? parser.Parse(cc.GetString()) : new Tag.AlwaysTrue(),
                e.TryGetProperty("minLevel", out var ml) ? Enum.Parse<BondLevel>(ml.GetString(), true) : BondLevel.Friend,
                e.GetProperty("skillId").GetString()));
        }
        Instance = this;
    }

    /// <summary>查找当前解锁的羁绊技能ID列表。</summary>
    public List<string> Lookup(IReadOnlySet<string> playerTags, IReadOnlySet<string> companionTags, BondLevel level)
        => _defs
            .Where(d => d.MinLevel <= level)
            .Where(d => d.PlayerCondition.Evaluate(playerTags))
            .Where(d => d.CompanionCondition.Evaluate(companionTags))
            .Select(d => d.SkillId)
            .ToList();
}

/// <summary>
/// BondSystem — 羁绊系统：羁绊等级（好感度映射）+ 羁绊技能解锁（标签组合）。
/// </summary>
public sealed class BondSystem
{
    private readonly Unit.Unit _player;
    private readonly Unit.Unit _companion;

    public BondSystem(Unit.Unit player, Unit.Unit companion)
    {
        _player = player;
        _companion = companion;
    }

    /// <summary>羁绊等级：≥100灵魂羁绊 / ≥80亲密 / ≥60朋友 / ≥40相识 / 陌生。</summary>
    public BondLevel CurrentLevel() => _companion.Affinity switch
    {
        >= 100 => BondLevel.Soulbond,
        >= 80 => BondLevel.Close,
        >= 60 => BondLevel.Friend,
        >= 40 => BondLevel.Acquaintance,
        _ => BondLevel.Stranger
    };

    /// <summary>羁绊技能解锁：标签组合 + 羁绊等级。</summary>
    public List<string> GetBondSkillIds()
        => BondSkillRegistry.Instance.Lookup(_player.ActiveTagIds, _companion.ActiveTagIds, CurrentLevel());
}

// ==================== 故事线 ====================

/// <summary>
/// StoryArc — 队友个人故事线：一个小型任务图按步骤推进。
/// </summary>
public sealed class StoryArc
{
    public string CompanionId { get; }
    public int CurrentStep { get; private set; }

    private readonly Graph.GraphEngine<Graph.QuestData> _questChain;

    public StoryArc(string companionId, Graph.GraphEngine<Graph.QuestData> chain)
    {
        CompanionId = companionId;
        _questChain = chain;
    }

    /// <summary>获取当前步骤可用的故事任务。</summary>
    public Graph.QuestNode GetCurrentQuest(IReadOnlySet<string> playerTags)
    {
        var nodes = _questChain.AllNodes;
        if (CurrentStep >= nodes.Count) return null;
        return nodes[CurrentStep] is Graph.QuestNode qn && qn.CanAccept(playerTags, 999) ? qn : null;
    }

    /// <summary>完成当前步，推进故事。</summary>
    public void Advance() => CurrentStep++;

    public bool IsComplete => CurrentStep >= _questChain.AllNodes.Count;
}

// ==================== 成长 ====================

/// <summary>
/// AllyGrowth — 队友成长：经验升级（职业成长曲线）+ 独立标签获取 + 装备（Buff化）。
/// </summary>
public sealed class AllyGrowth
{
    public const int EquipSlots = 4;   // 武器/防具/饰品/戒指

    private readonly Unit.Unit _companion;
    private readonly Equipment.EquipmentManager _equipManager;
    private readonly Equipment.Equip[] _equipped = new Equipment.Equip[EquipSlots];

    public AllyGrowth(Unit.Unit companion, Equipment.EquipmentManager equipManager = null)
    {
        _companion = companion;
        _equipManager = equipManager;
    }

    /// <summary>获取经验 → 升级（每级所需 = level×100）。</summary>
    public void GainExp(int amount)
    {
        _companion.Exp += amount;
        while (_companion.Exp >= ExpForNextLevel())
        {
            _companion.Exp -= ExpForNextLevel();
            LevelUp();
        }
    }

    private void LevelUp()
    {
        _companion.Level++;
        // 职业成长曲线：StatGrowth 按级叠加基础值
        var growth = _companion.CurrentClass?.StatGrowth;
        if (growth != null)
        {
            foreach (var (key, value) in growth)
                _companion.Stats.SetBase(key, _companion.Stats.GetBase(key) + value);
        }
    }

    private int ExpForNextLevel() => _companion.Level * 100;

    /// <summary>独立的标签获取（不同于玩家）。</summary>
    public void GrantQuestTag(string tagId)
    {
        _companion.QuestTagIds.Add(tagId);
        _companion.RecalculateTags();
    }

    /// <summary>装备到固定槽位（委托 EquipmentManager：装备=永久Buff，不进TagSet）。</summary>
    public bool Equip(Equipment.Equip equip, int slot)
    {
        if (slot < 0 || slot >= EquipSlots) return false;
        if (_equipped[slot] != null) _equipManager?.Unequip(_equipped[slot]);
        _equipped[slot] = equip;
        if (_equipManager != null) return _equipManager.Equip(equip).Success;

        // 无装备管理器回退：baseStats+effects 直接 Buff 化
        var effects = new List<IEffectDef>(equip.Effects);
        foreach (var (key, value) in equip.BaseStats)
            effects.Add(new StatMod(key, Operator.Add, value));
        _companion.BuffManager.AddBuff(Buff.BuffFactory.CreateFromEquip(equip.Id, equip.Name, effects));
        return true;
    }
}

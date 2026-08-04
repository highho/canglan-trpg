using GameCore.Effect;
using GameCore.EventBus;
using GameCore.Stats;

namespace GameCore.Unit;

/// <summary>九宫格站位（行0-2，列0-2）。站位修正与 DAMAGE_MOD.onGridPos 使用。</summary>
public sealed record GridPos(int Row, int Col);

/// <summary>
/// Unit — 统一模型。NPC、队友、怪物本质相同，偏向不同：
/// 角色差异 = 行为池 + 关系状态 + 偏向（role）。
/// 转换只是改 role + relationState + behaviorPool，不创建新 Unit。
/// </summary>
public sealed class Unit
{
    public string Id { get; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; }

    // ===== 角色偏向 / 关系 / 战斗模式 =====
    public UnitRole Role { get; set; }
    public RelationState RelationToPlayer { get; set; } = RelationState.Neutral;
    public CombatMode CombatMode { get; set; } = CombatMode.None;
    public int Affinity { get; set; }                  // 好感度（仅NPC/Ally）
    public bool IsDead { get; private set; }
    public GridPos GridPos { get; set; } = new(1, 1);

    // ===== 标签（共享） =====
    public Graph.RaceNode CurrentRace { get; private set; }
    public Graph.ClassNode CurrentClass { get; private set; }
    public HashSet<string> QuestTagIds { get; } = new();   // 任务标签（不可逆）
    public HashSet<string> TraitTagIds { get; } = new();   // 特质标签
    public IReadOnlySet<string> ActiveTagIds => _activeTagIds;
    public IReadOnlyList<Tag.Tag> ActiveTags => _activeTags;
    private HashSet<string> _activeTagIds = new();
    private List<Tag.Tag> _activeTags = new();

    // ===== 属性 / 情感 / Buff / 背包 =====
    public GameCore.Stats.Stats Stats { get; } = new();
    public EmotionSystem Emotion { get; }
    public Buff.BuffManager BuffManager { get; }
    public Item.Inventory Inventory { get; } = new();
    public World.SurvivalStats Survival { get; }

    // ===== 大地图位置 =====
    public World.MapPos WorldPos { get; set; } = new(0, 0);

    // ===== 行为池 =====
    public BehaviorPool SocialPool { get; set; }          // Monster 为 null
    public BehaviorPool CombatPool { get; set; }
    public BehaviorPool ActivePool { get; set; }

    // ===== 队友间关系 =====
    public Dictionary<Unit, int> AllyAffinities { get; } = new();  // -100 ~ 100

    // ===== 雇佣（仅 Mercenary 类型） =====
    public int HireCost { get; set; }                    // 雇佣价格（金币）
    public int ContractDuration { get; set; }            // 剩余合约天数/战斗次数，0=永久
    public bool IsMercenary { get; set; }                // true=雇佣兵，false=感情招募

    // ===== 经济 =====
    public int Gold { get; set; }                        // 持有金币（交易/修理/雇佣）

    // ===== 成长 =====
    public int Level { get; set; } = 1;                  // 等级（任务门槛/队友成长）
    public int Exp { get; set; }                         // 经验值

    // ===== 元数据（怪物掉落表/经验值等扩展数据） =====
    public Dictionary<string, object> Metadata { get; } = new();

    // ===== 依赖 =====
    private readonly Tag.TagFactory _tagFactory;
    private readonly EffectEngine _effectEngine;
    private readonly IEventBus _eventBus;

    // ===== 属性快照 =====
    private readonly Dictionary<string, StatValue> _tagStats = new();   // 标签层
    private readonly Dictionary<string, StatValue> _buffStats = new();  // Buff层（不进TagSet）
    private bool _hpWarned;

    public IEventBus EventBus => _eventBus;

    public Unit(string name, UnitRole role, Tag.TagFactory tagFactory, EffectEngine effectEngine, IEventBus bus)
    {
        Name = name;
        Role = role;
        _tagFactory = tagFactory;
        _effectEngine = effectEngine;
        _eventBus = bus;
        Emotion = new EmotionSystem(this, bus);
        BuffManager = new Buff.BuffManager(this, bus, effectEngine);
        Survival = new World.SurvivalStats(this, bus);

        CombatPool = BehaviorPools.DefaultCombatPool();
        ActivePool = role == UnitRole.Monster ? CombatPool : null;
    }

    // ==================== 标签 ====================

    public bool HasTag(string tagId) => _activeTagIds.Contains(tagId);

    /// <summary>
    /// recalculateTags — 无状态全量重建（运行时标签层核心方法）。
    /// 第一步：清除旧效果（订阅+属性快照）；第二步：重建标签ID集合（Set天然去重）；
    /// 第三步：工厂创建完整Tag实例；第四步：应用效果。
    /// </summary>
    public void RecalculateTags()
    {
        // 第一步：清除旧效果
        _eventBus.UnsubscribeAll(this);
        _tagStats.Clear();

        // 第二步：重建标签ID集合
        var newIds = new HashSet<string>();
        if (CurrentRace != null) newIds.UnionWith(CurrentRace.TagIds);
        if (CurrentClass != null) newIds.UnionWith(CurrentClass.TagIds);
        newIds.UnionWith(QuestTagIds);
        newIds.UnionWith(TraitTagIds);
        newIds.UnionWith(Emotion.ActiveEmotionIds());
        _activeTagIds = newIds;

        // 第三步：工厂创建完整Tag实例
        _activeTags = _tagFactory.CreateAll(newIds);

        // 第四步：应用效果
        _effectEngine.ApplyStatMods(this, _activeTags);
        _effectEngine.RegisterTriggers(this, _activeTags, _eventBus);

        _eventBus.Emit(EventTypes.TagChanged, this);
    }

    public void ChangeRace(Graph.RaceNode newRace)
    {
        // conflictTags 统一处理：新节点自带的冲突标签从任务/特质来源中清除
        if (newRace != null)
        {
            foreach (var conflict in newRace.ConflictTags)
            {
                QuestTagIds.Remove(conflict);
                TraitTagIds.Remove(conflict);
            }
        }
        CurrentRace = newRace;
        RecalculateTags();   // 旧种族标签自然消失
        _eventBus.Emit(EventTypes.RaceChanged, this);
    }

    public void ChangeClass(Graph.ClassNode newClass)
    {
        CurrentClass = newClass;
        RecalculateTags();
        _eventBus.Emit(EventTypes.ClassChanged, this);
    }

    /// <summary>施加情感并立即重建标签（情感 → 标签 → 行为权重链路）。</summary>
    public void ApplyEmotion(string emotionId, int intensity)
    {
        Emotion.ApplyEmotion(emotionId, intensity);
        RecalculateTags();
    }

    // ==================== 属性快照 ====================

    public void ApplyTagStat(string target, Operator op, float value)
        => GetOrCreate(_tagStats, target).Apply(op, value);

    public void ResetTagStats() => _tagStats.Clear();

    public void ApplyBuffStat(string target, Operator op, float value)
        => GetOrCreate(_buffStats, target).Apply(op, value);

    public void ResetBuffStats() => _buffStats.Clear();

    private static StatValue GetOrCreate(Dictionary<string, StatValue> dict, string key)
    {
        if (!dict.TryGetValue(key, out var sv)) dict[key] = sv = new StatValue();
        return sv;
    }

    /// <summary>最终属性 = 基础值 → 标签快照 + Buff快照 合并修正（同类效果叠加）。</summary>
    public float GetStat(string key)
    {
        var baseValue = Stats.GetBase(key);
        var hasSet = false;
        float setValue = 0f, add = 0f, multiply = 1f;
        foreach (var dict in new[] { _tagStats, _buffStats })
        {
            if (!dict.TryGetValue(key, out var sv)) continue;
            if (sv.Set.HasValue) { hasSet = true; setValue = sv.Set.Value; }
            add += sv.Add;
            multiply *= sv.Multiply;
        }
        return hasSet ? setValue : (baseValue + add) * multiply;
    }

    public int MaxHp => (int)GetStat("HP");
    public float Atk => GetStat("ATK");
    public float Def => GetStat("DEF");
    public float Spd => GetStat("SPD");
    public float HpPercent => MaxHp <= 0 ? 0f : Stats.Hp * 100f / MaxHp;

    // ==================== 战斗 ====================

    public void Heal(int amount)
    {
        if (IsDead) return;
        Stats.Hp = Math.Min(MaxHp, Stats.Hp + amount);
        if (HpPercent > 30f) _hpWarned = false;
    }

    /// <summary>
    /// 受到伤害。lethal=false（切磋/打劫）时 HP 归零不死亡，由战斗系统判定结束。
    /// </summary>
    public void TakeDamage(float amount, Unit attacker, IEventBus bus, bool lethal = true)
    {
        if (IsDead) return;
        Stats.Hp -= (int)Math.Max(1f, amount);

        if (Stats.Hp <= 0)
        {
            Stats.Hp = 0;
            if (lethal)
            {
                IsDead = true;
                bus?.EmitEvent(DeathEvent.Of(this, attacker));
            }
            return;
        }

        if (!_hpWarned && HpPercent <= 30f)
        {
            _hpWarned = true;
            bus?.Emit(EventTypes.HpBelow30, this);
        }
    }

    /// <summary>死亡（剧情/处决等非战斗路径）。</summary>
    public void Kill(Unit killer)
    {
        if (IsDead) return;
        Stats.Hp = 0;
        IsDead = true;
        _eventBus.EmitEvent(DeathEvent.Of(this, killer));
    }

    /// <summary>复活（轻度死亡惩罚模式：保留进度原地复活）。</summary>
    public void Revive(float hpPercent = 0.5f)
    {
        IsDead = false;
        Stats.Hp = Math.Max(1, (int)(MaxHp * Math.Clamp(hpPercent, 0.1f, 1f)));
    }

    /// <summary>切换至战斗行为池（切磋/打劫/袭杀/遇敌）。</summary>
    public void EnterCombat(CombatMode mode)
    {
        CombatMode = mode;
        ActivePool = CombatPool;
    }

    /// <summary>恢复社交行为池（战斗结束后）。</summary>
    public void ExitCombat()
    {
        CombatMode = CombatMode.None;
        ActivePool = Role is UnitRole.Npc or UnitRole.Ally or UnitRole.Player ? SocialPool : null;
    }

    /// <summary>获取对另一个队友的好感度（缺省0）。</summary>
    public int GetAllyAffinity(Unit other) => AllyAffinities.TryGetValue(other, out var v) ? v : 0;

    /// <summary>视野加成（标签 [夜视]/[鹰眼] 等的 VISION 效果，战争迷雾用）。</summary>
    public int GetVisionBonus() => (int)GetStat("VISION");

    public void AddAllyAffinity(Unit other, int delta)
        => AllyAffinities[other] = Math.Clamp(GetAllyAffinity(other) + delta, -100, 100);

    public void AddAffinity(int delta) => Affinity = Math.Clamp(Affinity + delta, -100, 100);

    public override string ToString() => $"{Name}[{Role}]{(IsDead ? "(死亡)" : "")}";
}

using GameCore.EventBus;
using GameCore.Effect;

namespace GameCore.Battle;

/// <summary>行动类型。</summary>
public enum ActionType { Move, Attack, Skill, Defend, Item, Pass, CoverAlly, BetrayAlly, Flee, CallHelp }

/// <summary>战斗回合阶段。</summary>
public enum BattlePhase { Init, PlayerTurn, EnemyTurn, NpcInterrupt, Resolve, BattleEnd }

/// <summary>Action — 战斗行动数据载体（执行由 BattleManager 统一负责）。</summary>
public sealed class BattleAction
{
    public ActionType Type { get; init; }
    public Unit.Unit Actor { get; init; }
    public List<Unit.Unit> Targets { get; init; } = new();
    public Skill.Skill Skill { get; init; }             // type==Skill 时使用
    public GridPosition TargetPos { get; init; }        // 技能作用原点
    public GridPosition MoveTarget { get; init; }       // type==Move 时使用
    public string ItemId { get; init; }                 // type==Item 时使用
}

/// <summary>战斗结果。</summary>
public sealed record BattleResult(bool PlayerWin, List<Unit.Unit> Deaths, List<Unit.Unit> Survivors);

/// <summary>
/// BattleManager — 双九宫格回合制完整管理。
/// 己方回合（移动+行动）→ 敌方回合（AI决策）→ 回合结束事件 → 胜负判定。
/// 支持切磋(SPAR)/打劫(ROB)不致死、袭杀(LETHAL)致死；掩护/背刺/NPC监听介入。
/// </summary>
public sealed class BattleManager
{
    public GridSystem Grid { get; }
    public IEventBus EventBus { get; }
    public BattlePhase CurrentPhase { get; private set; } = BattlePhase.Init;
    public List<Unit.Unit> Allies { get; }
    public List<Unit.Unit> Enemies { get; }
    public List<Unit.Unit> DeathList { get; } = new();
    public HashSet<Unit.Unit> FledUnits { get; } = new();
    public HashSet<Unit.Unit> Betrayers { get; } = new();
    public int TurnNumber { get; private set; }
    public Unit.CombatMode Mode { get; }

    /// <summary>参战单位的技能冷却管理器（战斗AI查询可用技能）。</summary>
    public Dictionary<Unit.Unit, Skill.CooldownManager> SkillManagers { get; } = new();
    public bool Paused { get; private set; }
    public BattleResult Result { get; private set; }

    /// <summary>玩家/队友行动选择钩子（缺省用 AI）。</summary>
    public Func<Unit.Unit, BattleAction> AllyActionSelector { get; set; }

    private readonly BattleAI _ai;
    private readonly Random _rng = new();
    private readonly EffectEngine _effectEngine;
    private readonly Dictionary<Unit.Unit, Unit.Unit> _covers = new();   // 被掩护者 → 掩护者

    public BattleManager(GridSystem grid, IEventBus bus, BattleAI ai, EffectEngine effectEngine,
        List<Unit.Unit> allies, List<Unit.Unit> enemies, Unit.CombatMode mode = Unit.CombatMode.Lethal)
    {
        Grid = grid;
        EventBus = bus;
        _ai = ai;
        _effectEngine = effectEngine;
        Allies = allies;
        Enemies = enemies;
        Mode = mode;
        bus.SubscribeWithOwner(EventTypes.UnitDeath, e =>
        {
            var dead = e.DeadUnit;
            if (dead != null && !DeathList.Contains(dead))
            {
                DeathList.Add(dead);
                GrantKillRewards(dead, e.Killer);
            }
        }, this);
    }

    /// <summary>击杀奖励：敌方怪物阵亡 → 击杀者（缺省首个存活己方）获得经验 + 掷骰掉落。</summary>
    private void GrantKillRewards(Unit.Unit dead, Unit.Unit killer)
    {
        if (!Enemies.Contains(dead)) return;
        var recipient = killer != null && Allies.Contains(killer)
            ? killer
            : Allies.FirstOrDefault(a => !a.IsDead);
        if (recipient == null) return;

        if (dead.Metadata.TryGetValue("expReward", out var expRaw) && expRaw is int exp)
            recipient.Exp += exp;

        if (dead.Metadata.TryGetValue("drops", out var dropsRaw) &&
            dropsRaw is List<Monster.LootEntry> drops)
        {
            var rolled = Monster.DropTable.Roll(drops, recipient, _rng);
            foreach (var (itemId, count) in Monster.DropTable.GenerateItems(rolled, _rng))
            {
                recipient.Inventory.Add(itemId, count);
                EventBus.Emit(EventTypes.ItemAcquired, recipient, itemId);
            }
        }
    }

    public void Start()
    {
        CurrentPhase = BattlePhase.Init;
        foreach (var u in Allies) u.EnterCombat(Mode);
        foreach (var u in Enemies) u.EnterCombat(Mode);
        EventBus.Emit(EventTypes.BattleStart, this);
        NextPhase();
    }

    public void NextPhase()
    {
        switch (CurrentPhase)
        {
            case BattlePhase.Init:
                CurrentPhase = BattlePhase.PlayerTurn;
                TurnNumber++;
                EventBus.Emit(EventTypes.TurnStart, TurnNumber);
                break;
            case BattlePhase.PlayerTurn:
                CurrentPhase = BattlePhase.EnemyTurn;
                break;
            case BattlePhase.EnemyTurn:
                CurrentPhase = BattlePhase.Resolve;
                break;
            case BattlePhase.Resolve:
                ResolveTurnEnd();
                CurrentPhase = IsBattleOver() ? BattlePhase.BattleEnd : BattlePhase.PlayerTurn;
                if (CurrentPhase == BattlePhase.BattleEnd) EndBattle();
                else TurnNumber++;
                break;
            case BattlePhase.NpcInterrupt:
                CurrentPhase = BattlePhase.Resolve;
                break;
            case BattlePhase.BattleEnd:
                break;
        }
    }

    /// <summary>运行一个完整回合：己方行动 → 敌方行动 → 结算。</summary>
    public void RunTurn()
    {
        if (CurrentPhase != BattlePhase.PlayerTurn) return;

        // 己方行动
        foreach (var ally in Allies.ToList())
        {
            if (IsOutOfCombat(ally)) continue;
            var action = AllyActionSelector?.Invoke(ally) ?? _ai.Decide(ally, this);
            ExecuteAction(ally, action);
            if (Paused) return;   // NPC对话介入，等待 Resume 后继续
        }
        NextPhase();

        // 敌方行动
        foreach (var enemy in Enemies.ToList())
        {
            if (IsOutOfCombat(enemy)) continue;
            var action = _ai.Decide(enemy, this);
            ExecuteAction(enemy, action);
            if (Paused) return;
        }
        NextPhase();   // → Resolve
        NextPhase();   // → 下一回合 PlayerTurn 或 BattleEnd
    }

    /// <summary>自动战斗直到分出胜负（演示/测试用）。</summary>
    public BattleResult RunToCompletion(int maxTurns = 50)
    {
        Start();
        while (CurrentPhase != BattlePhase.BattleEnd && TurnNumber <= maxTurns)
        {
            RunTurn();
            while (Paused) Resume();
        }
        if (CurrentPhase != BattlePhase.BattleEnd) EndBattle();
        return Result;
    }

    // ==================== 行动执行 ====================

    public void ExecuteAction(Unit.Unit actor, BattleAction action)
    {
        if (action == null || IsOutOfCombat(actor)) return;
        switch (action.Type)
        {
            case ActionType.Move: ExecuteMove(actor, action); break;
            case ActionType.Attack: ExecuteAttack(actor, action.Targets.FirstOrDefault()); break;
            case ActionType.Skill: ExecuteSkill(actor, action); break;
            case ActionType.Defend: ExecuteDefend(actor); break;
            case ActionType.CoverAlly: ExecuteCover(actor, action.Targets.FirstOrDefault()); break;
            case ActionType.BetrayAlly: ExecuteBetray(actor, action.Targets.FirstOrDefault()); break;
            case ActionType.Flee: ExecuteFlee(actor); break;
            case ActionType.CallHelp: EventBus.Emit("CALL_HELP", actor); break;
            case ActionType.Item: break;   // 消耗品效果由物品系统接管
            case ActionType.Pass: break;
        }
        EventBus.Emit(EventTypes.ActionExecuted, actor);
    }

    private void ExecuteMove(Unit.Unit actor, BattleAction action)
    {
        if (action.MoveTarget == null) return;
        if (Grid.GetAt(action.MoveTarget) != null) return;   // 目标格被占用
        Grid.RemoveUnit(actor);
        Grid.PlaceUnit(actor, action.MoveTarget);
    }

    private void ExecuteAttack(Unit.Unit actor, Unit.Unit target)
    {
        if (target == null || IsOutOfCombat(target)) return;

        // 掩护转移：声明过掩护的目标，伤害由掩护者接下
        if (_covers.TryGetValue(target, out var guardian)
            && !ReferenceEquals(guardian, actor) && !IsOutOfCombat(guardian))
        {
            _covers.Remove(target);
            EventBus.Emit(EventTypes.AllyCover, guardian, target);
            target = guardian;
        }

        var (damage, crit) = DamageCalculator.Calculate(actor, target, Grid, _rng);
        target.TakeDamage(damage, actor, EventBus, lethal: IsLethal);
        EventBus.Emit(EventTypes.DamageDealt, actor, target, (int)damage);
        if (crit) EventBus.Emit(EventTypes.DamageCrit, target, actor);
        if (!target.IsDead && target.Stats.Hp <= 0 && !IsLethal)
            EventBus.Emit(EventTypes.DamageDealt, actor, target);   // 切磋/打劫击倒
    }

    private void ExecuteSkill(Unit.Unit actor, BattleAction action)
    {
        var skill = action.Skill;
        if (skill == null || !skill.IsReady())
        {
            EventBus.Emit(EventTypes.SkillFailed, actor, skill?.Id);
            return;
        }

        var origin = action.TargetPos ?? Grid.FindPosition(actor);
        if (origin == null) return;
        var targets = Grid.GetTargets(origin, skill.TargetPattern)
            .Where(t => !IsOutOfCombat(t)).ToList();
        if (targets.Count == 0)
        {
            EventBus.Emit(EventTypes.SkillFailed, actor, skill.Id);
            return;
        }

        foreach (var t in targets)
        {
            if (skill.BaseDamage > 0)
            {
                var (damage, crit) = DamageCalculator.CalculateSkill(actor, skill, t, Grid, _rng);
                t.TakeDamage(damage, actor, EventBus, lethal: IsLethal);
                EventBus.Emit(EventTypes.DamageDealt, actor, t, (int)damage);
                if (crit) EventBus.Emit(EventTypes.DamageCrit, t, actor);
            }
            foreach (var effect in skill.Effects)
                _effectEngine.ApplyEffect(actor, t, effect, EventBus);
        }
        skill.Use();
        EventBus.Emit(EventTypes.SkillUsed, actor, skill);
    }

    private void ExecuteDefend(Unit.Unit actor)
    {
        // 防御姿态：1回合 DEF×2 临时Buff
        var def = new Buff.BuffDef("defending", "防御姿态", Buff.BuffType.Temporary, 1,
            new IEffectDef[] { new StatMod("DEF", Operator.Multiply, 2f) }, false, 1);
        actor.BuffManager.AddBuff(new Buff.Buff(def));
    }

    private void ExecuteCover(Unit.Unit actor, Unit.Unit target)
    {
        if (target == null) return;
        _covers[target] = actor;
        EventBus.Emit(EventTypes.AllyCover, actor, target);
    }

    private void ExecuteBetray(Unit.Unit actor, Unit.Unit target)
    {
        if (target == null) return;
        var (damage, _) = DamageCalculator.Calculate(actor, target, Grid, _rng);
        target.TakeDamage(damage, actor, EventBus, lethal: true);   // 背刺致死
        Betrayers.Add(actor);
        EventBus.Emit(EventTypes.AllyBetray, actor, target);
    }

    private void ExecuteFlee(Unit.Unit actor)
    {
        Grid.RemoveUnit(actor);
        FledUnits.Add(actor);
    }

    // ==================== 回合结算 / 胜负 ====================

    private void ResolveTurnEnd()
    {
        EventBus.Emit(EventTypes.TurnEnd, TurnNumber);   // Buff倒计时/技能冷却由订阅者处理
        // 情感自然衰减 → 活跃情感变化时重建标签
        foreach (var unit in Allies.Concat(Enemies))
        {
            if (IsOutOfCombat(unit)) continue;
            if (unit.Emotion.TickDecay()) unit.RecalculateTags();
        }
        _covers.Clear();   // 掩护声明仅持续一回合
    }

    public bool IsOutOfCombat(Unit.Unit unit)
        => unit.IsDead || unit.Stats.Hp <= 0 || FledUnits.Contains(unit);

    private bool IsLethal => Mode is Unit.CombatMode.Lethal or Unit.CombatMode.None;

    private bool IsBattleOver()
        => Allies.All(IsOutOfCombat) || Enemies.All(IsOutOfCombat);

    private void EndBattle()
    {
        var playerWin = Enemies.All(IsOutOfCombat);
        var survivors = Allies.Concat(Enemies).Where(u => !IsOutOfCombat(u)).ToList();
        Result = new BattleResult(playerWin, DeathList.ToList(), survivors);
        foreach (var u in Allies.Concat(Enemies))
            if (!u.IsDead) u.ExitCombat();
        EventBus.Emit(EventTypes.BattleEnd, Result);
        EventBus.UnsubscribeAll(this);
    }

    // ==================== NPC 监听介入 ====================

    public void Pause()
    {
        Paused = true;
        CurrentPhase = BattlePhase.NpcInterrupt;
    }

    public void Resume()
    {
        Paused = false;
        NextPhase();   // NpcInterrupt → Resolve
    }
}

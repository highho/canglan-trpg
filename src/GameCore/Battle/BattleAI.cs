using GameCore.Unit;

namespace GameCore.Battle;

/// <summary>
/// BattleAI — 战斗决策。同一引擎驱动怪物/NPC/队友：
/// 行为池选项 + 队友间动态选项（COVER_ALLY / BETRAY_ALLY）+ 技能选项 → 权重法决策。
/// </summary>
public sealed class BattleAI
{
    private readonly Behavior.BehaviorEngine _engine;
    private readonly Random _rng;

    public BattleAI(Behavior.BehaviorEngine engine, Random rng = null)
    {
        _engine = engine;
        _rng = rng ?? new Random();
    }

    public BattleAction Decide(Unit.Unit actor, BattleManager battle)
    {
        var baseOptions = (actor.ActivePool ?? actor.CombatPool)?.Options
                          ?? BehaviorPools.DefaultCombatPool().Options;
        var options = new List<BehaviorOption>(baseOptions);

        Unit.Unit coverTarget = null, betrayTarget = null;

        var otherAllies = battle.Allies
            .Where(a => !ReferenceEquals(a, actor) && !battle.IsOutOfCombat(a)).ToList();

        // 技能选项：存在可用主动技能时加入候选
        Skill.Skill usableSkill = null;
        if (battle.SkillManagers.TryGetValue(actor, out var cm))
        {
            usableSkill = cm.GetUsableSkills()
                .FirstOrDefault(s => s.Type is Skill.SkillType.Active or Skill.SkillType.Ultimate);
            if (usableSkill != null)
            {
                options.Add(new BehaviorOption("skill", "技能", 45,
                    new Dictionary<string, Dictionary<string, int>>
                    {
                        ["EMOTION"] = new() { ["愤怒"] = 10, ["恐惧"] = -10 }
                    }));
            }
        }

        // 队友间动态选项
        if (actor.Role == UnitRole.Ally && otherAllies.Count > 0)
        {
            // COVER_ALLY：权重 = 基础5 + (好感度/5) + 人格[勇敢]+10 + 人格[懦弱]-20 + 情感[担忧]+15
            coverTarget = otherAllies.OrderBy(a => a.HpPercent).First();
            options.Add(new BehaviorOption("cover_ally", "掩护队友",
                Math.Max(0, 5 + actor.GetAllyAffinity(coverTarget) / 5),
                new Dictionary<string, Dictionary<string, int>>
                {
                    ["PERSONALITY"] = new() { ["勇敢"] = 10, ["懦弱"] = -20 },
                    ["EMOTION"] = new() { ["担忧"] = 15, ["悲伤"] = 10 }
                }));

            // BETRAY_ALLY：触发条件 好感度 < -30；权重 = 30 + 人格[冷酷]+20 + 情感[愤怒]+15
            betrayTarget = otherAllies
                .Where(a => actor.GetAllyAffinity(a) < -30)
                .OrderBy(a => actor.GetAllyAffinity(a))
                .FirstOrDefault();
            if (betrayTarget != null)
            {
                options.Add(new BehaviorOption("betray_ally", "背刺队友", 30,
                    new Dictionary<string, Dictionary<string, int>>
                    {
                        ["PERSONALITY"] = new() { ["冷酷"] = 20, ["记仇"] = 15, ["忠诚"] = -40 },
                        ["EMOTION"] = new() { ["愤怒"] = 15 }
                    }));
            }
        }

        var chosen = _engine.Decide(actor.ActiveTags, options);
        return MapToAction(actor, chosen?.Id, battle, coverTarget, betrayTarget, usableSkill);
    }

    private BattleAction MapToAction(Unit.Unit actor, string optionId, BattleManager battle,
        Unit.Unit coverTarget, Unit.Unit betrayTarget, Skill.Skill usableSkill)
    {
        var enemies = battle.Enemies.Where(e => !battle.IsOutOfCombat(e)).ToList();
        var firstEnemy = enemies.FirstOrDefault();

        switch (optionId)
        {
            case "attack":
                if (firstEnemy == null) return new BattleAction { Type = ActionType.Pass, Actor = actor };
                return new BattleAction { Type = ActionType.Attack, Actor = actor, Targets = { firstEnemy } };

            case "defend":
                return new BattleAction { Type = ActionType.Defend, Actor = actor };

            case "flee":
                return new BattleAction { Type = ActionType.Flee, Actor = actor };

            case "call_help":
                return new BattleAction { Type = ActionType.CallHelp, Actor = actor };

            case "skill" when usableSkill != null:
                GridPosition targetPos = null;
                if (usableSkill.TargetPattern == Skill.TargetPattern.Single && firstEnemy != null)
                    targetPos = battle.Grid.FindPosition(firstEnemy);
                else if (usableSkill.TargetPattern == Skill.TargetPattern.All)
                    targetPos = battle.Grid.FindPosition(actor);   // ALL 取对面全场
                else if (firstEnemy != null)
                    targetPos = battle.Grid.FindPosition(firstEnemy);
                return new BattleAction
                {
                    Type = ActionType.Skill,
                    Actor = actor,
                    Skill = usableSkill,
                    TargetPos = targetPos
                };

            case "cover_ally" when coverTarget != null:
                return new BattleAction { Type = ActionType.CoverAlly, Actor = actor, Targets = { coverTarget } };

            case "betray_ally" when betrayTarget != null:
                return new BattleAction { Type = ActionType.BetrayAlly, Actor = actor, Targets = { betrayTarget } };

            default:
                return firstEnemy != null
                    ? new BattleAction { Type = ActionType.Attack, Actor = actor, Targets = { firstEnemy } }
                    : new BattleAction { Type = ActionType.Pass, Actor = actor };
        }
    }

    /// <summary>怪物换位评估：交换后收益 > 0 才换位（MVP：前排空位/规避集火的简化评估）。</summary>
    public bool ShouldSwap(Unit.Unit self, Unit.Unit adjacentAlly, GridSystem grid)
    {
        var pa = grid.FindPosition(self);
        var pb = grid.FindPosition(adjacentAlly);
        if (pa == null || pb == null) return false;
        // 评估1：自己在后排而相邻友方在前排空挡 → 换位抢前排增伤
        if (pa.Row == 2 && pb.Row == 1 && self.Spd >= adjacentAlly.Spd) return true;
        // 评估2：自己血量危急且在前排 → 退到后排
        if (pa.Row == 1 && pb.Row == 2 && self.HpPercent < 30) return true;
        return false;
    }
}

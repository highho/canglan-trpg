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

        // 根据个性标签选择最优目标（攻击/技能共用）
        var primaryTarget = SelectByPersonality(actor, enemies, battle);

        switch (optionId)
        {
            case "attack":
                if (primaryTarget == null) return new BattleAction { Type = ActionType.Pass, Actor = actor };
                return new BattleAction { Type = ActionType.Attack, Actor = actor, Targets = { primaryTarget } };

            case "defend":
                return new BattleAction { Type = ActionType.Defend, Actor = actor };

            case "flee":
                return new BattleAction { Type = ActionType.Flee, Actor = actor };

            case "call_help":
                return new BattleAction { Type = ActionType.CallHelp, Actor = actor };

            case "skill" when usableSkill != null:
                GridPosition targetPos = null;
                var skillTarget = primaryTarget ?? firstEnemy;
                if (usableSkill.TargetPattern == Skill.TargetPattern.Single && skillTarget != null)
                    targetPos = battle.Grid.FindPosition(skillTarget);
                else if (usableSkill.TargetPattern == Skill.TargetPattern.All)
                    targetPos = battle.Grid.FindPosition(actor);
                else if (skillTarget != null)
                    targetPos = battle.Grid.FindPosition(skillTarget);
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
                return primaryTarget != null
                    ? new BattleAction { Type = ActionType.Attack, Actor = actor, Targets = { primaryTarget } }
                    : new BattleAction { Type = ActionType.Pass, Actor = actor };
        }
    }

    /// <summary>根据个性标签从敌人列表中选最优目标。</summary>
    private static Unit.Unit SelectByPersonality(Unit.Unit actor, List<Unit.Unit> enemies, BattleManager battle)
    {
        if (enemies.Count == 0) return null;
        if (enemies.Count == 1) return enemies[0];

        var tags = actor.ActiveTagIds;

        // 天敌/狩猎本能：优先选择种族标签为[野兽]的目标
        if (tags.Contains("猎狼人") || tags.Contains("屠兽者") || tags.Contains("驯兽大师"))
        {
            var beast = enemies.FirstOrDefault(e => e.ActiveTagIds.Contains("野兽"));
            if (beast != null) return beast;
        }

        // 圣职驱魔：优先选择[亡灵][黑暗][恶魔]
        if (tags.Contains("圣殿骑士") || tags.Contains("光明誓约"))
        {
            var corrupt = enemies.FirstOrDefault(e =>
                e.ActiveTagIds.Contains("亡灵") || e.ActiveTagIds.Contains("黑暗") || e.ActiveTagIds.Contains("恶魔"));
            if (corrupt != null) return corrupt;
        }

        // 复仇/记仇：优先攻击上次攻击过自己的敌人（存在伤害记录）
        if (tags.Contains("记仇") || tags.Contains("愤怒"))
        {
            // 找血量最高的敌人（愤怒状态下想打最强的那一个）
            var strongest = enemies.OrderByDescending(e => e.Stats.Hp).First();
            if (strongest != null) return strongest;
        }

        // 狡猾/贪婪：优先攻击低血量目标（补刀收割）
        if (tags.Contains("狡猾") || tags.Contains("贪婪"))
        {
            var low = enemies.OrderBy(e => e.HpPercent).First();
            if (low != null && low.HpPercent < 50) return low;
        }

        // 懦弱：优先攻击 SPD 最低的目标（不敢打快的）
        if (tags.Contains("懦弱"))
        {
            var slowest = enemies.OrderBy(e => e.Spd).First();
            if (slowest != null) return slowest;
        }

        // 忠诚/勇敢：优先保护 Boss——如果有 BOSS 则攻击离 Boss 最近的敌人？简化：血量百分比最低的敌人
        if (tags.Contains("忠诚") || tags.Contains("勇敢"))
        {
            return enemies.OrderBy(e => e.HpPercent).First();
        }

        return enemies[0];   // 默认：第一个活着的敌人
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

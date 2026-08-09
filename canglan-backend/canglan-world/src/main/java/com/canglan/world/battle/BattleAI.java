package com.canglan.world.battle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.canglan.data.skill.Skill;
import com.canglan.data.skill.SkillType;
import com.canglan.data.skill.TargetPattern;
import com.canglan.world.behavior.BehaviorEngine;
import com.canglan.world.unit.BehaviorOption;
import com.canglan.world.unit.BehaviorPools;
import com.canglan.world.unit.Unit;
import com.canglan.world.unit.UnitRole;

/**
 * BattleAI — 战斗决策。同一引擎驱动怪物/NPC/队友：
 * 行为池选项 + 队友间动态选项（COVER_ALLY / BETRAY_ALLY）+ 技能选项 → 权重法决策。
 * 对应 C# BattleAI。
 */
public final class BattleAI {

    private final BehaviorEngine engine;
    private final Random rng;

    public BattleAI(BehaviorEngine engine, Random rng) {
        this.engine = engine;
        this.rng = rng != null ? rng : new Random();
    }

    public BattleAI(BehaviorEngine engine) { this(engine, null); }

    public BattleAction decide(Unit actor, BattleManager battle) {
        List<BehaviorOption> baseOptions = (actor.activePool() != null
                ? actor.activePool() : actor.combatPool() != null
                ? actor.combatPool() : BehaviorPools.defaultCombatPool()).options();
        List<BehaviorOption> options = new ArrayList<>(baseOptions);

        Unit coverTarget = null, betrayTarget = null;

        List<Unit> otherAllies = new ArrayList<>();
        for (Unit a : battle.allies())
            if (a != actor && !battle.isOutOfCombat(a)) otherAllies.add(a);

        // 技能选项：存在可用主动技能时加入候选
        Skill usableSkill = null;
        var cm = battle.skillManagers().get(actor);
        if (cm != null) {
            usableSkill = cm.getUsableSkills().stream()
                    .filter(s -> s.type() == SkillType.ACTIVE || s.type() == SkillType.ULTIMATE)
                    .findFirst().orElse(null);
            if (usableSkill != null) {
                options.add(new BehaviorOption("skill", "技能", 45, Map.of(
                        "EMOTION", Map.of("愤怒", 10, "恐惧", -10))));
            }
        }

        // 队友间动态选项
        if (actor.role() == UnitRole.ALLY && !otherAllies.isEmpty()) {
            // COVER_ALLY：权重 = 基础5 + (好感度/5) + 人格[勇敢]+10 + 人格[懦弱]-20 + 情感[担忧]+15
            coverTarget = otherAllies.stream()
                    .min(Comparator.comparingDouble(Unit::hpPercent)).orElse(null);
            if (coverTarget != null) {
                options.add(new BehaviorOption("cover_ally", "掩护队友",
                        Math.max(0, 5 + actor.getAllyAffinity(coverTarget) / 5), Map.of(
                                "PERSONALITY", Map.of("勇敢", 10, "懦弱", -20),
                                "EMOTION", Map.of("担忧", 15, "悲伤", 10))));
            }

            // BETRAY_ALLY：触发条件 好感度 < -30；权重 = 30 + 人格[冷酷]+20 + 情感[愤怒]+15
            betrayTarget = otherAllies.stream()
                    .filter(a -> actor.getAllyAffinity(a) < -30)
                    .min(Comparator.comparingInt(actor::getAllyAffinity))
                    .orElse(null);
            if (betrayTarget != null) {
                options.add(new BehaviorOption("betray_ally", "背刺队友", 30, Map.of(
                        "PERSONALITY", Map.of("冷酷", 20, "记仇", 15, "忠诚", -40),
                        "EMOTION", Map.of("愤怒", 15))));
            }
        }

        BehaviorOption chosen = engine.decide(actor.activeTags(), options);
        return mapToAction(actor, chosen != null ? chosen.id() : null, battle,
                coverTarget, betrayTarget, usableSkill);
    }

    private BattleAction mapToAction(Unit actor, String optionId, BattleManager battle,
                                     Unit coverTarget, Unit betrayTarget, Skill usableSkill) {
        // 按行动者所属阵营取对面存活目标（C# 原版固定读 battle.Enemies，怪物会误击己方，此处修正）
        List<Unit> source = battle.allies().contains(actor) ? battle.enemies() : battle.allies();
        List<Unit> enemies = new ArrayList<>();
        for (Unit e : source)
            if (!battle.isOutOfCombat(e)) enemies.add(e);
        Unit firstEnemy = enemies.isEmpty() ? null : enemies.get(0);

        // 根据个性标签选择最优目标（攻击/技能共用）
        Unit primaryTarget = selectByPersonality(actor, enemies);

        if (optionId == null) optionId = "";
        switch (optionId) {
            case "attack":
                if (primaryTarget == null) return new BattleAction(ActionType.PASS, actor);
                return new BattleAction(ActionType.ATTACK, actor).addTarget(primaryTarget);

            case "defend":
                return new BattleAction(ActionType.DEFEND, actor);

            case "flee":
                return new BattleAction(ActionType.FLEE, actor);

            case "call_help":
                return new BattleAction(ActionType.CALL_HELP, actor);

            case "skill":
                if (usableSkill == null) break;
                GridPosition targetPos = null;
                Unit skillTarget = primaryTarget != null ? primaryTarget : firstEnemy;
                if (usableSkill.targetPattern() == TargetPattern.SINGLE && skillTarget != null)
                    targetPos = battle.grid().findPosition(skillTarget);
                else if (usableSkill.targetPattern() == TargetPattern.ALL)
                    targetPos = battle.grid().findPosition(actor);
                else if (skillTarget != null)
                    targetPos = battle.grid().findPosition(skillTarget);
                return new BattleAction(ActionType.SKILL, actor)
                        .setSkill(usableSkill).setTargetPos(targetPos);

            case "cover_ally":
                if (coverTarget != null)
                    return new BattleAction(ActionType.COVER_ALLY, actor).addTarget(coverTarget);
                break;

            case "betray_ally":
                if (betrayTarget != null)
                    return new BattleAction(ActionType.BETRAY_ALLY, actor).addTarget(betrayTarget);
                break;

            default:
                break;
        }
        return primaryTarget != null
                ? new BattleAction(ActionType.ATTACK, actor).addTarget(primaryTarget)
                : new BattleAction(ActionType.PASS, actor);
    }

    /** 根据个性标签从敌人列表中选最优目标。 */
    private static Unit selectByPersonality(Unit actor, List<Unit> enemies) {
        if (enemies.isEmpty()) return null;
        if (enemies.size() == 1) return enemies.get(0);

        var tags = actor.activeTagIds();

        // 天敌/狩猎本能：优先选择种族标签为[野兽]的目标
        if (tags.contains("猎狼人") || tags.contains("屠兽者") || tags.contains("驯兽大师")) {
            Unit beast = enemies.stream()
                    .filter(e -> e.activeTagIds().contains("野兽"))
                    .findFirst().orElse(null);
            if (beast != null) return beast;
        }

        // 圣职驱魔：优先选择[亡灵][黑暗][恶魔]
        if (tags.contains("圣殿骑士") || tags.contains("光明誓约")) {
            Unit corrupt = enemies.stream()
                    .filter(e -> e.activeTagIds().contains("亡灵")
                            || e.activeTagIds().contains("黑暗")
                            || e.activeTagIds().contains("恶魔"))
                    .findFirst().orElse(null);
            if (corrupt != null) return corrupt;
        }

        // 复仇/记仇：愤怒状态下想打最强的那一个（血量最高）
        if (tags.contains("记仇") || tags.contains("愤怒")) {
            return enemies.stream()
                    .max(Comparator.comparingInt(e -> e.stats().hp()))
                    .orElse(null);
        }

        // 狡猾/贪婪：优先攻击低血量目标（补刀收割）
        if (tags.contains("狡猾") || tags.contains("贪婪")) {
            Unit low = enemies.stream()
                    .min(Comparator.comparingDouble(Unit::hpPercent)).orElse(null);
            if (low != null && low.hpPercent() < 50) return low;
        }

        // 懦弱：优先攻击 SPD 最低的目标（不敢打快的）
        if (tags.contains("懦弱")) {
            return enemies.stream()
                    .min(Comparator.comparingDouble(Unit::spd)).orElse(null);
        }

        // 忠诚/勇敢：优先保护 Boss——简化为血量百分比最低的敌人
        if (tags.contains("忠诚") || tags.contains("勇敢")) {
            return enemies.stream()
                    .min(Comparator.comparingDouble(Unit::hpPercent)).orElse(null);
        }

        return enemies.get(0);   // 默认：第一个活着的敌人
    }

    /** 怪物换位评估：交换后收益 > 0 才换位（MVP：前排空位/规避集火的简化评估）。 */
    public boolean shouldSwap(Unit self, Unit adjacentAlly, GridSystem grid) {
        GridPosition pa = grid.findPosition(self);
        GridPosition pb = grid.findPosition(adjacentAlly);
        if (pa == null || pb == null) return false;
        // 评估1：自己在后排而相邻友方在前排空挡 → 换位抢前排增伤
        if (pa.row() == 2 && pb.row() == 1 && self.spd() >= adjacentAlly.spd()) return true;
        // 评估2：自己血量危急且在前排 → 退到后排
        if (pa.row() == 1 && pb.row() == 2 && self.hpPercent() < 30) return true;
        return false;
    }
}

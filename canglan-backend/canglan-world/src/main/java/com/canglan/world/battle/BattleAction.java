package com.canglan.world.battle;

import java.util.ArrayList;
import java.util.List;

import com.canglan.data.skill.Skill;
import com.canglan.world.unit.Unit;

/**
 * Action — 战斗行动数据载体（执行由 BattleManager 统一负责）。对应 C# BattleAction。
 */
public final class BattleAction {

    private final ActionType type;
    private final Unit actor;
    private final List<Unit> targets = new ArrayList<>();
    private Skill skill;              // type==SKILL 时使用
    private GridPosition targetPos;   // 技能作用原点
    private GridPosition moveTarget;  // type==MOVE 时使用
    private String itemId;            // type==ITEM 时使用

    public BattleAction(ActionType type, Unit actor) {
        this.type = type;
        this.actor = actor;
    }

    public ActionType type() { return type; }
    public Unit actor() { return actor; }
    public List<Unit> targets() { return targets; }
    public Unit firstTarget() { return targets.isEmpty() ? null : targets.get(0); }
    public BattleAction addTarget(Unit t) { if (t != null) targets.add(t); return this; }

    public Skill skill() { return skill; }
    public BattleAction setSkill(Skill s) { this.skill = s; return this; }

    public GridPosition targetPos() { return targetPos; }
    public BattleAction setTargetPos(GridPosition p) { this.targetPos = p; return this; }

    public GridPosition moveTarget() { return moveTarget; }
    public BattleAction setMoveTarget(GridPosition p) { this.moveTarget = p; return this; }

    public String itemId() { return itemId; }
    public BattleAction setItemId(String id) { this.itemId = id; return this; }
}

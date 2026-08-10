package com.canglan.world.battle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import com.canglan.core.effect.Operator;
import com.canglan.core.effect.StatMod;
import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.data.buff.Buff;
import com.canglan.data.buff.BuffDef;
import com.canglan.data.buff.BuffType;
import com.canglan.data.monster.LootEntry;
import com.canglan.data.skill.CooldownManager;
import com.canglan.data.skill.Skill;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.monster.DropTable;
import com.canglan.world.unit.CombatMode;
import com.canglan.world.unit.Unit;

/**
 * BattleManager — 双九宫格回合制完整管理。
 * 己方回合（移动+行动）→ 敌方回合（AI决策）→ 回合结束事件 → 胜负判定。
 * 支持切磋(SPAR)/打劫(ROB)不致死、袭杀(LETHAL)致死；掩护/背刺/NPC监听介入。
 * 对应 C# BattleManager。
 */
public final class BattleManager {

    private final GridSystem grid;
    private final EventBus eventBus;
    private BattlePhase currentPhase = BattlePhase.INIT;
    private final List<Unit> allies;
    private final List<Unit> enemies;
    private final List<Unit> deathList = new ArrayList<>();
    private final Set<Unit> fledUnits = new HashSet<>();
    private final Set<Unit> betrayers = new HashSet<>();
    private int turnNumber;
    private final CombatMode mode;

    /** 参战单位的技能冷却管理器（战斗AI查询可用技能）。 */
    private final Map<Unit, CooldownManager> skillManagers = new HashMap<>();
    private boolean paused;
    private BattleResult result;

    /** 玩家/队友行动选择钩子（缺省用 AI）。 */
    private Function<Unit, BattleAction> allyActionSelector;

    private final BattleAI ai;
    private final Random rng;
    private final EffectEngine effectEngine;
    private final Map<Unit, Unit> covers = new HashMap<>();   // 被掩护者 → 掩护者

    /** 连击追踪：目标 → (上次攻击者, 连续命中次数)。 */
    private record ComboEntry(Unit lastAttacker, int count) {}
    private final Map<Unit, ComboEntry> comboTracker = new HashMap<>();

    public BattleManager(GridSystem grid, EventBus bus, BattleAI ai, EffectEngine effectEngine,
                         List<Unit> allies, List<Unit> enemies, CombatMode mode, Random rng) {
        this.grid = grid;
        this.eventBus = bus;
        this.ai = ai;
        this.effectEngine = effectEngine;
        this.allies = allies;
        this.enemies = enemies;
        this.mode = mode;
        this.rng = rng != null ? rng : new Random();
        bus.subscribeWithOwner(EventTypes.UNIT_DEATH, e -> {
            Unit dead = e.get("deadUnit");
            Unit killer = e.get("killer");
            if (dead != null && !deathList.contains(dead)) {
                deathList.add(dead);
                grantKillRewards(dead, killer);
            }
        }, this);
    }

    public BattleManager(GridSystem grid, EventBus bus, BattleAI ai, EffectEngine effectEngine,
                         List<Unit> allies, List<Unit> enemies, CombatMode mode) {
        this(grid, bus, ai, effectEngine, allies, enemies, mode, null);
    }

    public BattleManager(GridSystem grid, EventBus bus, BattleAI ai, EffectEngine effectEngine,
                         List<Unit> allies, List<Unit> enemies) {
        this(grid, bus, ai, effectEngine, allies, enemies, CombatMode.LETHAL, null);
    }

    // ==================== 访问器 ====================

    public GridSystem grid() { return grid; }
    public EventBus eventBus() { return eventBus; }
    public BattlePhase currentPhase() { return currentPhase; }
    public List<Unit> allies() { return allies; }
    public List<Unit> enemies() { return enemies; }
    public List<Unit> deathList() { return deathList; }
    public Set<Unit> fledUnits() { return fledUnits; }
    public Set<Unit> betrayers() { return betrayers; }
    public int turnNumber() { return turnNumber; }
    public CombatMode mode() { return mode; }
    public Map<Unit, CooldownManager> skillManagers() { return skillManagers; }
    public boolean isPaused() { return paused; }
    public BattleResult result() { return result; }
    public void setAllyActionSelector(Function<Unit, BattleAction> selector) { this.allyActionSelector = selector; }

    /** 击杀奖励：敌方怪物阵亡 → 击杀者（缺省首个存活己方）获得经验 + 掷骰掉落。 */
    private void grantKillRewards(Unit dead, Unit killer) {
        if (!enemies.contains(dead)) return;
        Unit recipient = killer != null && allies.contains(killer)
                ? killer
                : allies.stream().filter(a -> !a.isDead()).findFirst().orElse(null);
        if (recipient == null) return;

        if (dead.metadata().get("expReward") instanceof Integer exp) {
            double expMul = dead.metadata().get("expMul") instanceof Double dem ? dem : 1.0;
            recipient.setExp(recipient.exp() + (int) Math.round(exp * expMul));
        }

        // 金币：怪物按难度倍率掉落（低难度略低，高难度丰收）
        double goldMul = dead.metadata().get("goldMul") instanceof Double dgm ? dgm : 1.0;
        if (goldMul > 0 && dead.metadata().get("templateId") instanceof String) {
            int goldBase = 3 + recipient.level() * 2;
            recipient.setGold(recipient.gold() + (int) Math.round(goldBase * goldMul * (0.6 + rng.nextDouble() * 0.8)));
        }

        if (dead.metadata().get("drops") instanceof List<?> dropsRaw && !dropsRaw.isEmpty()
                && dropsRaw.get(0) instanceof LootEntry) {
            @SuppressWarnings("unchecked")
            List<LootEntry> drops = (List<LootEntry>) dropsRaw;
            List<LootEntry> rolled = DropTable.roll(drops, recipient, rng);
            for (var drop : DropTable.generateItems(rolled, rng)) {
                recipient.inventory().add(drop.itemId(), drop.count());
                eventBus.emit(EventTypes.ITEM_ACQUIRED, recipient, drop.itemId());
            }
        }
    }

    // ==================== 回合流程 ====================

    public void start() {
        currentPhase = BattlePhase.INIT;
        for (Unit u : allies) u.enterCombat(mode);
        for (Unit u : enemies) u.enterCombat(mode);
        eventBus.emit(EventTypes.BATTLE_START, this);
        nextPhase();
    }

    public void nextPhase() {
        switch (currentPhase) {
            case INIT -> {
                currentPhase = BattlePhase.PLAYER_TURN;
                turnNumber++;
                eventBus.emit(EventTypes.TURN_START, turnNumber);
            }
            case PLAYER_TURN -> currentPhase = BattlePhase.ENEMY_TURN;
            case ENEMY_TURN -> currentPhase = BattlePhase.RESOLVE;
            case RESOLVE -> {
                resolveTurnEnd();
                currentPhase = isBattleOver() ? BattlePhase.BATTLE_END : BattlePhase.PLAYER_TURN;
                if (currentPhase == BattlePhase.BATTLE_END) endBattle();
                else turnNumber++;
            }
            case NPC_INTERRUPT -> currentPhase = BattlePhase.RESOLVE;
            case BATTLE_END -> { }
        }
    }

    /** 运行一个完整回合：己方行动 → 敌方行动 → 结算。 */
    public void runTurn() {
        if (currentPhase != BattlePhase.PLAYER_TURN) return;

        // 己方行动
        for (Unit ally : new ArrayList<>(allies)) {
            if (isOutOfCombat(ally)) continue;
            BattleAction action = allyActionSelector != null
                    ? allyActionSelector.apply(ally) : ai.decide(ally, this);
            executeAction(ally, action);
            if (paused) return;   // NPC对话介入，等待 resume 后继续
        }
        nextPhase();

        // 敌方行动
        for (Unit enemy : new ArrayList<>(enemies)) {
            if (isOutOfCombat(enemy)) continue;
            BattleAction action = ai.decide(enemy, this);
            executeAction(enemy, action);
            if (paused) return;
        }
        nextPhase();   // → Resolve
        nextPhase();   // → 下一回合 PlayerTurn 或 BattleEnd
    }

    /** 自动战斗直到分出胜负（演示/测试用）。 */
    public BattleResult runToCompletion(int maxTurns) {
        start();
        while (currentPhase != BattlePhase.BATTLE_END && turnNumber <= maxTurns) {
            runTurn();
            while (paused) resume();
        }
        if (currentPhase != BattlePhase.BATTLE_END) endBattle();
        return result;
    }

    public BattleResult runToCompletion() { return runToCompletion(50); }

    // ==================== 行动执行 ====================

    public void executeAction(Unit actor, BattleAction action) {
        if (action == null || isOutOfCombat(actor)) return;
        switch (action.type()) {
            case MOVE -> executeMove(actor, action);
            case ATTACK -> executeAttack(actor, action.firstTarget());
            case SKILL -> executeSkill(actor, action);
            case DEFEND -> executeDefend(actor);
            case COVER_ALLY -> executeCover(actor, action.firstTarget());
            case BETRAY_ALLY -> executeBetray(actor, action.firstTarget());
            case FLEE -> executeFlee(actor);
            case CALL_HELP -> eventBus.emit("CALL_HELP", actor);
            case ITEM -> { }   // 消耗品效果由物品系统接管
            case PASS -> { }
        }
        eventBus.emit(EventTypes.ACTION_EXECUTED, actor);
    }

    private void executeMove(Unit actor, BattleAction action) {
        if (action.moveTarget() == null) return;
        if (grid.getAt(action.moveTarget()) != null) return;   // 目标格被占用
        grid.removeUnit(actor);
        grid.placeUnit(actor, action.moveTarget());
    }

    private void executeAttack(Unit actor, Unit target) {
        if (target == null || isOutOfCombat(target)) return;

        // 掩护转移：声明过掩护的目标，伤害由掩护者接下
        Unit guardian = covers.get(target);
        if (guardian != null && guardian != actor && !isOutOfCombat(guardian)) {
            covers.remove(target);
            eventBus.emit(EventTypes.ALLY_COVER, guardian, target);
            target = guardian;
        }

        DamageCalculator.DamageResult dr = DamageCalculator.calculate(actor, target, grid, rng);
        float damage = dr.damage();

        // 连击追踪：同一攻击者连续攻击同一目标 → 伤害递增
        float comboMult = 1f;
        ComboEntry comboEntry = comboTracker.get(target);
        if (comboEntry != null && comboEntry.lastAttacker() == actor) {
            comboTracker.put(target, new ComboEntry(actor, comboEntry.count() + 1));
            comboMult = 1f + comboEntry.count() * 0.12f;   // 2连击+12%, 3连击+24%...
            if (comboEntry.count() >= 1)
                eventBus.emit("COMBO_TRIGGERED", actor, target, comboEntry.count() + 1);
        } else {
            comboTracker.put(target, new ComboEntry(actor, 1));
        }
        damage *= comboMult;

        target.takeDamage(damage, actor, eventBus, isLethal());
        eventBus.emit(EventTypes.DAMAGE_DEALT, actor, target, (int) damage);
        if (dr.crit()) eventBus.emit(EventTypes.DAMAGE_CRIT, target, actor);
        if (!target.isDead() && target.stats().hp() <= 0 && !isLethal())
            eventBus.emit(EventTypes.DAMAGE_DEALT, actor, target);   // 切磋/打劫击倒
    }

    private void executeSkill(Unit actor, BattleAction action) {
        Skill skill = action.skill();
        if (skill == null || !skill.isReady()) {
            eventBus.emit(EventTypes.SKILL_FAILED, actor, skill != null ? skill.id() : null);
            return;
        }

        GridPosition origin = action.targetPos() != null ? action.targetPos() : grid.findPosition(actor);
        if (origin == null) return;
        List<Unit> targets = new ArrayList<>();
        for (Unit t : grid.getTargets(origin, skill.targetPattern()))
            if (!isOutOfCombat(t)) targets.add(t);
        if (targets.isEmpty()) {
            eventBus.emit(EventTypes.SKILL_FAILED, actor, skill.id());
            return;
        }

        for (Unit t : targets) {
            if (skill.baseDamage() > 0) {
                DamageCalculator.DamageResult dr = DamageCalculator.calculateSkill(actor, skill, t, grid, rng);
                t.takeDamage(dr.damage(), actor, eventBus, isLethal());
                eventBus.emit(EventTypes.DAMAGE_DEALT, actor, t, (int) dr.damage());
                if (dr.crit()) eventBus.emit(EventTypes.DAMAGE_CRIT, t, actor);
            }
            for (var effect : skill.effects())
                effectEngine.applyEffect(actor, t, effect, eventBus);
        }
        skill.use();
        eventBus.emit(EventTypes.SKILL_USED, actor, skill);
    }

    private void executeDefend(Unit actor) {
        // 防御姿态：1回合 DEF×2 临时Buff
        BuffDef def = new BuffDef("defending", "防御姿态", BuffType.TEMPORARY, 1,
                List.of(new StatMod("DEF", Operator.MULTIPLY, 2f)), false, 1);
        actor.buffManager().addBuff(new Buff(def));
    }

    private void executeCover(Unit actor, Unit target) {
        if (target == null) return;
        covers.put(target, actor);
        eventBus.emit(EventTypes.ALLY_COVER, actor, target);
    }

    private void executeBetray(Unit actor, Unit target) {
        if (target == null) return;
        DamageCalculator.DamageResult dr = DamageCalculator.calculate(actor, target, grid, rng);
        target.takeDamage(dr.damage(), actor, eventBus, true);   // 背刺致死
        betrayers.add(actor);
        eventBus.emit(EventTypes.ALLY_BETRAY, actor, target);
    }

    private void executeFlee(Unit actor) {
        grid.removeUnit(actor);
        fledUnits.add(actor);
    }

    // ==================== 回合结算 / 胜负 ====================

    private void resolveTurnEnd() {
        eventBus.emit(EventTypes.TURN_END, turnNumber);   // Buff倒计时/技能冷却由订阅者处理
        comboTracker.clear();   // 回合结束连击计数归零
        // 情感自然衰减 → 活跃情感变化时重建标签
        for (Unit unit : allies) decayEmotion(unit);
        for (Unit unit : enemies) decayEmotion(unit);
        covers.clear();   // 掩护声明仅持续一回合
    }

    private void decayEmotion(Unit unit) {
        if (isOutOfCombat(unit)) return;
        if (unit.emotion().tickDecay()) unit.recalculateTags();
    }

    public boolean isOutOfCombat(Unit unit) {
        return unit.isDead() || unit.stats().hp() <= 0 || fledUnits.contains(unit);
    }

    private boolean isLethal() {
        return mode == CombatMode.LETHAL || mode == CombatMode.NONE;
    }

    private boolean isBattleOver() {
        return allies.stream().allMatch(this::isOutOfCombat)
                || enemies.stream().allMatch(this::isOutOfCombat);
    }

    private void endBattle() {
        boolean playerWin = enemies.stream().allMatch(this::isOutOfCombat);
        List<Unit> survivors = new ArrayList<>();
        for (Unit u : allies) if (!isOutOfCombat(u)) survivors.add(u);
        for (Unit u : enemies) if (!isOutOfCombat(u)) survivors.add(u);
        result = new BattleResult(playerWin, new ArrayList<>(deathList), survivors);
        for (Unit u : allies) if (!u.isDead()) u.exitCombat();
        for (Unit u : enemies) if (!u.isDead()) u.exitCombat();
        eventBus.emit(EventTypes.BATTLE_END, result);
        eventBus.unsubscribeAll(this);
    }

    // ==================== NPC 监听介入 ====================

    public void pause() {
        paused = true;
        currentPhase = BattlePhase.NPC_INTERRUPT;
    }

    public void resume() {
        paused = false;
        nextPhase();   // NpcInterrupt → Resolve
    }
}

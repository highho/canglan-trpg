package com.canglan.world.battle;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.canglan.core.eventbus.EventBusImpl;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.core.tag.TagFactory;
import com.canglan.data.bootstrap.Registries;
import com.canglan.data.bootstrap.RegistryInitializer;
import com.canglan.data.buff.BuffFactory;
import com.canglan.data.monster.LootEntry;
import com.canglan.data.monster.MonsterTemplate;
import com.canglan.data.skill.DamageType;
import com.canglan.data.skill.Skill;
import com.canglan.data.skill.SkillType;
import com.canglan.data.skill.TargetPattern;
import com.canglan.world.BiomeType;
import com.canglan.world.WorldMap;
import com.canglan.world.behavior.BehaviorEngine;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.monster.AreaConfig;
import com.canglan.world.monster.DropTable;
import com.canglan.world.monster.MonsterFactory;
import com.canglan.world.monster.MonsterSpawner;
import com.canglan.world.monster.SpawnEntry;
import com.canglan.world.unit.CombatMode;
import com.canglan.world.unit.GridPos;
import com.canglan.world.unit.RelationState;
import com.canglan.world.unit.Unit;
import com.canglan.world.unit.UnitRole;

/**
 * P5 冒烟验证：GridSystem/DamageCalculator/BattleManager/BattleAI/MonsterFactory（无外部测试框架）。
 * 验收：三种战斗模式（切磋/打劫/袭杀）回合正确。
 * 用法: java com.canglan.world.battle.BattleSmokeTest &lt;dataDir&gt;
 */
public final class BattleSmokeTest {

    private static int passed;
    private static int failed;
    private static Registries r;
    private static MonsterTemplate tpl;

    public static void main(String[] args) {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");
        System.out.println("数据目录: " + dataDir.toAbsolutePath());

        r = RegistryInitializer.initialize(dataDir);
        tpl = r.monsters.getAll().iterator().next();
        check("怪物模板注册表已加载", r.monsters.size() > 0);

        testGridSystem();
        testMonsterFactory();
        testDropTable();
        testDamageCalculator();
        testThreeModes();
        testTurnFlowEvents();
        testDefendSkillCombo();
        testCoverBetrayFlee();
        testKillRewards();
        testBattleAI();
        testMonsterSpawner();

        System.out.println();
        System.out.println("通过: " + passed + "  失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 工具 ====================

    private static Unit hero(String name, float atk, float def, int hp) {
        Unit u = new Unit(name, UnitRole.PLAYER, new TagFactory(r.tags),
                new EffectEngine(new BuffFactory(r.buffs)), new EventBusImpl(), r.items);
        u.stats().setBase("HP", hp);
        u.stats().setBase("ATK", atk);
        u.stats().setBase("DEF", def);
        u.stats().setHp(u.maxHp());
        return u;
    }

    private static Unit heroWithBus(String name, float atk, float def, int hp, EventBusImpl bus) {
        Unit u = new Unit(name, UnitRole.PLAYER, new TagFactory(r.tags),
                new EffectEngine(new BuffFactory(r.buffs)), bus, r.items);
        u.stats().setBase("HP", hp);
        u.stats().setBase("ATK", atk);
        u.stats().setBase("DEF", def);
        u.stats().setHp(u.maxHp());
        return u;
    }

    private static Unit monsterWithBus(float atk, float def, int hp, EventBusImpl bus) {
        Unit m = new Unit("沙袋怪", UnitRole.MONSTER, new TagFactory(r.tags),
                new EffectEngine(new BuffFactory(r.buffs)), bus, r.items);
        m.stats().setBase("HP", hp);
        m.stats().setBase("ATK", atk);
        m.stats().setBase("DEF", def);
        m.stats().setHp(m.maxHp());
        return m;
    }

    private static BattleManager manager(EventBusImpl bus, List<Unit> allies, List<Unit> enemies,
                                         CombatMode mode, GridSystem grid) {
        BehaviorEngine engine = new BehaviorEngine(new Random(99));
        BattleAI ai = new BattleAI(engine, new Random(99));
        BattleManager bm = new BattleManager(grid, bus, ai,
                new EffectEngine(new BuffFactory(r.buffs)), allies, enemies, mode, new Random(42));
        grid.placeUnit(allies.get(0), new GridPosition(1, 2, Side.ALLY));
        for (int i = 0; i < enemies.size(); i++)
            grid.placeUnit(enemies.get(i), new GridPosition(1, i + 1, Side.ENEMY));
        for (int i = 1; i < allies.size(); i++)
            grid.placeUnit(allies.get(i), new GridPosition(1, i + 1, Side.ALLY));
        return bm;
    }

    // ==================== GridSystem ====================

    private static void testGridSystem() {
        EventBusImpl bus = new EventBusImpl();
        GridSystem grid = new GridSystem();
        Unit a = heroWithBus("甲", 10, 0, 100, bus);
        Unit b = heroWithBus("乙", 10, 0, 100, bus);
        Unit e = monsterWithBus(10, 0, 100, bus);

        grid.placeUnit(a, new GridPosition(1, 2, Side.ALLY));
        grid.placeUnit(b, new GridPosition(2, 2, Side.ALLY));
        grid.placeUnit(e, new GridPosition(1, 1, Side.ENEMY));

        check("放置/查询格位", grid.getAt(new GridPosition(1, 2, Side.ALLY)) == a);
        check("findPosition 反查", new GridPosition(2, 2, Side.ALLY).equals(grid.findPosition(b)));
        check("Unit.gridPos 同步（前排）", a.gridPos().equals(new GridPos(1, 2)));
        check("getRow 取整排", grid.getRow(Side.ALLY, 1).size() == 1 && grid.getRow(Side.ALLY, 2).get(0) == b);
        check("getColumn 取整列", grid.getColumn(Side.ALLY, 2).size() == 2);
        check("getAll 取全场", grid.getAll(Side.ENEMY).size() == 1);
        check("ALL 模式取对面全场", grid.getTargets(new GridPosition(1, 2, Side.ALLY), TargetPattern.ALL).get(0) == e);
        check("ADJACENT 模式取相邻（含纵向）", grid.getTargets(new GridPosition(1, 2, Side.ALLY), TargetPattern.ADJACENT).equals(List.of(b)));
        check("相邻友方（同行左右邻）", grid.getAdjacentAllies(a).isEmpty());

        grid.swapUnits(a, b);
        check("换位后前后排互换", grid.findPosition(a).row() == 2 && grid.findPosition(b).row() == 1);
        check("前排 +15% 修正", GridSystem.getPositionModifier(new GridPos(1, 1)) == 1.15f);
        check("后排无修正", GridSystem.getPositionModifier(new GridPos(2, 1)) == 1.0f);

        grid.removeUnit(e);
        check("移除后格位为空", grid.getAt(new GridPosition(1, 1, Side.ENEMY)) == null);
    }

    // ==================== MonsterFactory ====================

    private static void testMonsterFactory() {
        EventBusImpl bus = new EventBusImpl();
        MonsterFactory factory = new MonsterFactory(new TagFactory(r.tags),
                new EffectEngine(new BuffFactory(r.buffs)), bus, r.items, r.monsters, new Random(1));
        Unit m = factory.create(tpl);
        check("工厂创建怪物角色=MONSTER", m.role() == UnitRole.MONSTER);
        check("怪物满血出生", m.stats().hp() == m.maxHp() && m.maxHp() > 0);
        check("怪物固定敌对", m.relationToPlayer() == RelationState.HOSTILE);
        check("metadata 挂载模板引用", tpl.id().equals(m.metadata().get("templateId"))
                && m.metadata().containsKey("drops")
                && m.metadata().get("expReward") instanceof Integer);
        check("战斗池以 monster_ 前缀命名", m.combatPool().id().equals("monster_" + tpl.id()));
        check("怪物激活池=战斗池", m.activePool() == m.combatPool());
        check("按模板ID创建", factory.create(tpl.id()).name().equals(tpl.name()));
    }

    // ==================== DropTable ====================

    private static void testDropTable() {
        EventBusImpl bus = new EventBusImpl();
        Unit killer = heroWithBus("击杀者", 10, 0, 100, bus);
        List<LootEntry> entries = List.of(
                new LootEntry("必掉物", 1.0f, 2, 3, null),
                new LootEntry("条件物", 1.0f, 1, 1, "幸运"));
        List<DropTable.Drop> drops = DropTable.generateItems(DropTable.roll(entries, killer, new Random(7)), new Random(7));
        check("无条件必掉物命中", drops.stream().anyMatch(d -> d.itemId().equals("必掉物")));
        check("条件标签不满足不掉", drops.stream().noneMatch(d -> d.itemId().equals("条件物")));
        DropTable.Drop sure = drops.stream().filter(d -> d.itemId().equals("必掉物")).findFirst().orElseThrow();
        check("掉落数量在 min~max 间", sure.count() >= 2 && sure.count() <= 3);

        killer.traitTagIds().add("幸运");
        killer.recalculateTags();
        check("[幸运]标签激活", killer.hasTag("幸运"));
        List<DropTable.Drop> lucky = DropTable.generateItems(DropTable.roll(entries, killer, new Random(7)), new Random(7));
        check("满足条件标签后掉落", lucky.stream().anyMatch(d -> d.itemId().equals("条件物")));
    }

    // ==================== DamageCalculator ====================

    private static void testDamageCalculator() {
        EventBusImpl bus = new EventBusImpl();
        Unit attacker = heroWithBus("攻", 20, 0, 100, bus);
        Unit defender = heroWithBus("守", 0, 5, 100, bus);
        attacker.setGridPos(new GridPos(1, 1));   // 前排

        DamageCalculator.DamageResult front = DamageCalculator.calculate(attacker, defender, null, new Random(11));
        check("前排伤害 = 20×1.15-5 = 18", Math.abs(front.damage() - 18f) < 0.01 && !front.crit());

        attacker.setGridPos(new GridPos(2, 1));   // 后排
        DamageCalculator.DamageResult back = DamageCalculator.calculate(attacker, defender, null, new Random(11));
        check("后排伤害 = 20-5 = 15", Math.abs(back.damage() - 15f) < 0.01);

        Unit tank = heroWithBus("铁壁", 0, 999, 100, bus);
        DamageCalculator.DamageResult floor = DamageCalculator.calculate(attacker, tank, null, new Random(11));
        check("伤害下限为1", floor.damage() == 1f);

        Skill fireball = new Skill("test_fireball", "测试火球", SkillType.ACTIVE, 2,
                TargetPattern.SINGLE, List.of(), null, 1, 30, DamageType.PHYSICAL);
        attacker.setGridPos(new GridPos(1, 1));
        DamageCalculator.DamageResult sk = DamageCalculator.calculateSkill(attacker, fireball, defender, null, new Random(11));
        check("技能伤害 = (30+20×0.5)×1.15-5 = 41", Math.abs(sk.damage() - 41f) < 0.01);

        Skill trueHit = new Skill("test_true", "测试真伤", SkillType.ACTIVE, 0,
                TargetPattern.SINGLE, List.of(), null, 1, 30, DamageType.TRUE);
        DamageCalculator.DamageResult tr = DamageCalculator.calculateSkill(attacker, trueHit, defender, null, new Random(11));
        check("真实伤害无视防御", Math.abs(tr.damage() - 46f) < 0.01);
    }

    // ==================== 三种战斗模式（P5 验收核心） ====================

    private static void testThreeModes() {
        // 袭杀 LETHAL：致死
        EventBusImpl busL = new EventBusImpl();
        Unit p1 = heroWithBus("袭杀者", 50, 0, 100, busL);
        Unit m1 = monsterWithBus(5, 0, 30, busL);
        BattleManager lethal = manager(busL, List.of(p1), List.of(m1), CombatMode.LETHAL, new GridSystem());
        BattleResult resL = lethal.runToCompletion(20);
        check("LETHAL 玩家胜", resL.playerWin() && lethal.currentPhase() == BattlePhase.BATTLE_END);
        check("LETHAL 怪物真死亡", m1.isDead() && lethal.deathList().contains(m1));
        check("LETHAL 存活者仅玩家", resL.survivors().equals(List.of(p1)));
        check("结束后退出战斗模式", p1.combatMode() == CombatMode.NONE);

        // 切磋 SPAR：不致死
        EventBusImpl busS = new EventBusImpl();
        Unit p2 = heroWithBus("切磋者", 50, 0, 100, busS);
        Unit m2 = monsterWithBus(5, 0, 30, busS);
        BattleManager spar = manager(busS, List.of(p2), List.of(m2), CombatMode.SPAR, new GridSystem());
        BattleResult resS = spar.runToCompletion(20);
        check("SPAR 玩家胜", resS.playerWin() && spar.currentPhase() == BattlePhase.BATTLE_END);
        check("SPAR 击倒不致死", m2.stats().hp() <= 0 && !m2.isDead() && spar.deathList().isEmpty());

        // 打劫 ROB：不致死
        EventBusImpl busR = new EventBusImpl();
        Unit p3 = heroWithBus("打劫者", 50, 0, 100, busR);
        Unit m3 = monsterWithBus(5, 0, 30, busR);
        BattleManager rob = manager(busR, List.of(p3), List.of(m3), CombatMode.ROB, new GridSystem());
        BattleResult resR = rob.runToCompletion(20);
        check("ROB 玩家胜", resR.playerWin() && rob.currentPhase() == BattlePhase.BATTLE_END);
        check("ROB 击倒不致死", m3.stats().hp() <= 0 && !m3.isDead() && rob.deathList().isEmpty());
    }

    // ==================== 回合流程事件 ====================

    private static void testTurnFlowEvents() {
        EventBusImpl bus = new EventBusImpl();
        Unit p = heroWithBus("回合玩家", 20, 0, 100, bus);
        Unit m = monsterWithBus(5, 0, 40, bus);
        int[] counters = new int[5];   // BATTLE_START / TURN_START / TURN_END / ACTION / BATTLE_END
        bus.subscribe(EventTypes.BATTLE_START, e -> counters[0]++);
        bus.subscribe(EventTypes.TURN_START, e -> counters[1]++);
        bus.subscribe(EventTypes.TURN_END, e -> counters[2]++);
        bus.subscribe(EventTypes.ACTION_EXECUTED, e -> counters[3]++);
        bus.subscribe(EventTypes.BATTLE_END, e -> counters[4]++);

        BattleManager bm = manager(bus, List.of(p), List.of(m), CombatMode.SPAR, new GridSystem());
        bm.runToCompletion(20);

        check("BATTLE_START 恰好一次", counters[0] == 1);
        check("BATTLE_END 恰好一次", counters[4] == 1);
        check("TURN_START 开战恰好一次", counters[1] == 1);
        check("TURN_END 与已结算回合数相等", counters[2] == bm.turnNumber() && counters[2] >= 1);
        check("每次行动都发射 ACTION_EXECUTED", counters[3] >= 2);
        check("双方都行动过（伤害双向）", p.stats().hp() < 100 && m.stats().hp() < 40);
    }

    // ==================== 防御/技能/连击 ====================

    private static void testDefendSkillCombo() {
        EventBusImpl bus = new EventBusImpl();
        Unit p = heroWithBus("防御者", 20, 5, 100, bus);
        Unit m = monsterWithBus(5, 5, 500, bus);
        GridSystem grid = new GridSystem();
        BattleManager bm = manager(bus, List.of(p), List.of(m), CombatMode.SPAR, grid);
        bm.start();

        // 防御：DEF×2 临时Buff
        bm.executeAction(p, new BattleAction(ActionType.DEFEND, p));
        check("防御姿态 DEF×2", Math.abs(p.getStat("DEF") - 10f) < 0.01);
        check("防御Buff已挂载", p.buffManager().hasBuff("defending"));

        // 技能：冷却生效 + SKILL_USED
        Skill fireball = new Skill("test_fireball", "测试火球", SkillType.ACTIVE, 2,
                TargetPattern.SINGLE, List.of(), null, 1, 30, DamageType.PHYSICAL);
        int[] skillUsed = {0};
        bus.subscribe(EventTypes.SKILL_USED, e -> skillUsed[0]++);
        int hpBefore = m.stats().hp();
        bm.executeAction(p, new BattleAction(ActionType.SKILL, p)
                .setSkill(fireball).setTargetPos(grid.findPosition(m)));
        check("技能命中扣血", m.stats().hp() < hpBefore);
        check("技能进入冷却", fireball.currentCooldown() == 2 && !fireball.isReady());
        check("SKILL_USED 发射", skillUsed[0] == 1);
        int[] failed = {0};
        bus.subscribe(EventTypes.SKILL_FAILED, e -> failed[0]++);
        bm.executeAction(p, new BattleAction(ActionType.SKILL, p)
                .setSkill(fireball).setTargetPos(grid.findPosition(m)));
        check("冷却中技能失败", failed[0] == 1);

        // 连击：同一攻击者连续攻击同一目标 → 第二击 +12%
        int[] combo = {0};
        bus.subscribe("COMBO_TRIGGERED", e -> combo[0]++);
        Unit m2 = monsterWithBus(5, 5, 500, bus);
        grid.placeUnit(m2, new GridPosition(2, 1, Side.ENEMY));
        int hp0 = m2.stats().hp();
        bm.executeAction(p, new BattleAction(ActionType.ATTACK, p).addTarget(m2));
        int d1 = hp0 - m2.stats().hp();
        bm.executeAction(p, new BattleAction(ActionType.ATTACK, p).addTarget(m2));
        int d2 = hp0 - d1 - m2.stats().hp();
        check("连击触发事件", combo[0] == 1);
        check("2连击伤害递增 12%", d2 > d1 && Math.abs(d2 - d1 * 1.12f) < 1.0f);
    }

    // ==================== 掩护/背刺/逃跑 ====================

    private static void testCoverBetrayFlee() {
        // 掩护：声明掩护后，对目标的伤害转移给掩护者
        EventBusImpl bus = new EventBusImpl();
        Unit guard = heroWithBus("卫士", 10, 0, 100, bus);
        Unit vip = heroWithBus("要人", 10, 0, 100, bus);
        Unit foe = monsterWithBus(10, 0, 100, bus);
        GridSystem grid = new GridSystem();
        BattleManager bm = manager(bus, List.of(guard, vip), List.of(foe), CombatMode.LETHAL, grid);
        bm.start();
        int[] coverEvents = {0};
        bus.subscribe(EventTypes.ALLY_COVER, e -> coverEvents[0]++);

        bm.executeAction(guard, new BattleAction(ActionType.COVER_ALLY, guard).addTarget(vip));
        check("掩护声明事件", coverEvents[0] == 1);
        bm.executeAction(foe, new BattleAction(ActionType.ATTACK, foe).addTarget(vip));
        check("伤害转移给掩护者", guard.stats().hp() < 100 && vip.stats().hp() == 100);
        check("掩护触发事件（共2次）", coverEvents[0] == 2);

        // 背刺：切磋模式下也致死
        EventBusImpl bus2 = new EventBusImpl();
        Unit traitor = heroWithBus("叛徒", 100, 0, 100, bus2);
        Unit victim = heroWithBus("受害者", 5, 0, 20, bus2);
        Unit dummy = monsterWithBus(1, 0, 1000, bus2);
        GridSystem grid2 = new GridSystem();
        BattleManager bm2 = manager(bus2, List.of(traitor, victim), List.of(dummy), CombatMode.SPAR, grid2);
        bm2.start();
        int[] betrayEvents = {0};
        bus2.subscribe(EventTypes.ALLY_BETRAY, e -> betrayEvents[0]++);
        bm2.executeAction(traitor, new BattleAction(ActionType.BETRAY_ALLY, traitor).addTarget(victim));
        check("背刺致死（即使切磋模式）", victim.isDead());
        check("背刺者被记录", bm2.betrayers().contains(traitor));
        check("背刺事件发射", betrayEvents[0] == 1);

        // 逃跑：离场 + 全员逃跑直接判负方败
        EventBusImpl bus3 = new EventBusImpl();
        Unit pw = heroWithBus("胜者", 10, 0, 100, bus3);
        Unit coward = monsterWithBus(5, 0, 100, bus3);
        GridSystem grid3 = new GridSystem();
        BattleManager bm3 = manager(bus3, List.of(pw), List.of(coward), CombatMode.LETHAL, grid3);
        bm3.start();
        bm3.executeAction(coward, new BattleAction(ActionType.FLEE, coward));
        check("逃跑者离格并记录", grid3.findPosition(coward) == null && bm3.fledUnits().contains(coward));
        check("逃跑者脱离战斗", bm3.isOutOfCombat(coward));
        bm3.nextPhase();   // → ENEMY_TURN
        bm3.nextPhase();   // → RESOLVE
        bm3.nextPhase();   // → 胜负判定
        check("敌方全逃 → 玩家胜", bm3.currentPhase() == BattlePhase.BATTLE_END
                && bm3.result() != null && bm3.result().playerWin());
    }

    // ==================== 击杀奖励 ====================

    private static void testKillRewards() {
        EventBusImpl bus = new EventBusImpl();
        MonsterFactory factory = new MonsterFactory(new TagFactory(r.tags),
                new EffectEngine(new BuffFactory(r.buffs)), bus, r.items, r.monsters, new Random(1));
        Unit player = heroWithBus("赏金猎人", 999, 0, 100, bus);
        Unit monster = factory.create(tpl);
        GridSystem grid = new GridSystem();
        BattleManager bm = manager(bus, List.of(player), List.of(monster), CombatMode.LETHAL, grid);

        int exp0 = player.exp();
        int gold0 = player.gold();
        int[] acquired = {0};
        bus.subscribe(EventTypes.ITEM_ACQUIRED, e -> acquired[0]++);

        bm.runToCompletion(20);
        check("击杀后怪物死亡", monster.isDead());
        check("经验奖励到账", player.exp() == exp0 + tpl.expReward());
        check("金币奖励到账", player.gold() > gold0);
        check("掉落物数量与 ITEM_ACQUIRED 一致",
                acquired[0] == DropTable.generateItems(DropTable.roll(tpl.drops(), player, new Random(42)), new Random(42)).size());
    }

    // ==================== BattleAI ====================

    private static void testBattleAI() {
        EventBusImpl bus = new EventBusImpl();
        BehaviorEngine engine = new BehaviorEngine(new Random(3));
        BattleAI ai = new BattleAI(engine, new Random(3));

        // 普通玩家：默认战斗池 attack 权重最高
        Unit brave = heroWithBus("勇士", 10, 0, 100, bus);
        Unit foe = monsterWithBus(5, 0, 100, bus);
        GridSystem grid = new GridSystem();
        BattleManager bm = manager(bus, List.of(brave), List.of(foe), CombatMode.LETHAL, grid);
        bm.start();
        BattleAction action = ai.decide(brave, bm);
        check("AI 默认决策攻击", action.type() == ActionType.ATTACK && action.firstTarget() == foe);

        // 懦弱人格：defend(30+20=50) > attack(50-10=40) → 防御
        EventBusImpl bus2 = new EventBusImpl();
        Unit coward = heroWithBus("胆小鬼", 10, 0, 100, bus2);
        coward.traitTagIds().add("懦弱");
        coward.recalculateTags();
        Unit foe2 = monsterWithBus(5, 0, 100, bus2);
        BattleManager bm2 = manager(bus2, List.of(coward), List.of(foe2), CombatMode.LETHAL, new GridSystem());
        bm2.start();
        BattleAction cowardAction = ai.decide(coward, bm2);
        check("懦弱人格改为防御", cowardAction.type() == ActionType.DEFEND);

        // 换位评估：危急前排 → 退后排
        GridSystem grid3 = new GridSystem();
        Unit front = heroWithBus("前排", 10, 0, 100, bus);
        Unit back = heroWithBus("后排", 10, 0, 100, bus);
        grid3.placeUnit(front, new GridPosition(1, 1, Side.ALLY));
        grid3.placeUnit(back, new GridPosition(2, 1, Side.ALLY));
        front.stats().setHp(10);   // 10% 危急
        check("危急前排同意退后排", ai.shouldSwap(front, back, grid3));
    }

    // ==================== MonsterSpawner ====================

    private static void testMonsterSpawner() {
        EventBusImpl bus = new EventBusImpl();
        MonsterFactory factory = new MonsterFactory(new TagFactory(r.tags),
                new EffectEngine(new BuffFactory(r.buffs)), bus, r.items, r.monsters, new Random(5));
        WorldMap map = new WorldMap(20, 20);
        MonsterSpawner spawner = new MonsterSpawner(factory, map, new Random(5));
        AreaConfig zone = new AreaConfig("plains_zone", BiomeType.PLAINS, 1, 5,
                List.of(new SpawnEntry(tpl, 2, 3, 1f)));
        List<Unit> spawned = spawner.spawn(zone);
        check("刷怪数量在 min~max 间", spawned.size() >= 2 && spawned.size() <= 3);
        check("刷出的怪物有坐标与角色", spawned.stream().allMatch(m ->
                m.role() == UnitRole.MONSTER && m.worldPos() != null
                        && m.worldPos().x() >= 0 && m.worldPos().x() < 20));
    }

    // ==================== 断言 ====================

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}

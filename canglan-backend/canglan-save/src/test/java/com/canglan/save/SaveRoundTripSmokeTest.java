package com.canglan.save;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.canglan.core.eventbus.EventBusImpl;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.core.graph.ClassNode;
import com.canglan.core.graph.RaceNode;
import com.canglan.core.tag.TagFactory;
import com.canglan.data.bootstrap.Registries;
import com.canglan.data.bootstrap.RegistryInitializer;
import com.canglan.data.buff.BuffFactory;
import com.canglan.world.FogRow;
import com.canglan.world.GameTime;
import com.canglan.world.MapLayer;
import com.canglan.world.MapPos;
import com.canglan.world.WorldMap;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.unit.Unit;
import com.canglan.world.unit.UnitRole;

/**
 * P3/P4 冒烟验证：存档 → 读档 → 重建属性一致 + 迷雾/地图/时钟恢复（无外部测试框架）。
 * 用法: java com.canglan.save.SaveRoundTripSmokeTest &lt;dataDir&gt; [saveDir]
 */
public final class SaveRoundTripSmokeTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");
        Path saveDir = Path.of(args.length > 1 ? args[1] : "build-save-test");
        System.out.println("数据目录: " + dataDir.toAbsolutePath());
        System.out.println("存档目录: " + saveDir.toAbsolutePath());

        Registries r = RegistryInitializer.initialize(dataDir);
        EventBusImpl bus = new EventBusImpl();
        BuffFactory buffFactory = new BuffFactory(r.buffs);
        EffectEngine engine = new EffectEngine(buffFactory);
        TagFactory tagFactory = new TagFactory(r.tags);

        // ========== 构建玩家（源头数据） ==========
        Unit player = new Unit("测试者", UnitRole.PLAYER, tagFactory, engine, bus, r.items);
        player.changeRace((RaceNode) r.raceGraph.getNode("human"));
        player.changeClass((ClassNode) r.classGraph.getNode("warrior"));
        player.setLevel(3);
        player.setExp(120);
        player.setGold(500);
        player.inventory().add("__smoke_sword__", 2);
        player.inventory().add("__smoke_bread__", 5);
        player.survival().restore(80, 60, 50, 90);

        // 存档前快照
        Set<String> beforeTags = Set.copyOf(player.activeTagIds());
        float beforeHp = player.getStat("HP");
        float beforeAtk = player.getStat("ATK");
        double beforeCarry = player.carryCapacity();
        check("玩家具有种族+职业标签", beforeTags.size() >= 2);

        // ========== 队友（含好感度；存档契约：AllyAffinities 只存队友间好感度） ==========
        Unit companion = new Unit("小伴", UnitRole.ALLY, tagFactory, engine, bus, r.items);
        companion.changeRace((RaceNode) r.raceGraph.getNode("human"));
        companion.setAffinity(30);
        Unit companion2 = new Unit("阿二", UnitRole.ALLY, tagFactory, engine, bus, r.items);
        companion2.changeRace((RaceNode) r.raceGraph.getNode("human"));
        companion.addAllyAffinity(companion2, 10);

        // ========== 存档 ==========
        // 世界与迷雾（P4）：玩家在村庄，迷雾已探索出一块视野
        WorldMap beforeMap = new WorldMap(50, 50);
        player.setWorldPos(new MapPos(25, 25));
        beforeMap.currentFog().update(player);
        List<FogRow> beforeFog = beforeMap.currentFog().exportRows();

        Map<String, Integer> npcAffinities = Map.of("npc_smith", 25);
        SaveData data = SaveManager.capture(player, List.of(companion, companion2),
                npcAffinities, null, null, Map.of("met_elder", true),
                12345L, "冒烟营地", 3, 14, "Surface", "Plains",
                List.of("__smoke_sword__"), Set.of("__smoke_bread__"), null, 42,
                beforeFog);

        SaveManager sm = new SaveManager(saveDir);
        check("存档写入成功", sm.save(1, data));
        check("槽位列表含 slot 1", sm.listSlots().stream().anyMatch(s -> s.slot() == 1));

        // ========== 读档重建 ==========
        int[] gameLoaded = {0};
        bus.subscribe(EventTypes.GAME_LOADED, e -> gameLoaded[0]++);
        GameTime time = new GameTime();
        GameLoader loader = new GameLoader(sm, r.raceGraph, r.classGraph, tagFactory, engine, bus, r.items,
                50, 50, time);
        GameState gs = loader.load(1);

        Unit loaded = gs.player();
        check("GAME_LOADED 事件已发射", gameLoaded[0] == 1);
        check("玩家名一致", "测试者".equals(loaded.name()));
        check("种族ID一致", loaded.currentRace() != null && "human".equals(loaded.currentRace().id()));
        check("职业ID一致", loaded.currentClass() != null && "warrior".equals(loaded.currentClass().id()));
        check("等级/经验/金币一致",
                loaded.level() == 3 && loaded.exp() == 120 && loaded.gold() == 500);

        // 核心验收：标签重建一致 → 属性重建一致
        check("活跃标签集合一致", beforeTags.equals(loaded.activeTagIds()));
        check("HP 属性重建一致", Math.abs(beforeHp - loaded.getStat("HP")) < 1e-3);
        check("ATK 属性重建一致", Math.abs(beforeAtk - loaded.getStat("ATK")) < 1e-3);
        check("负重容量重建一致", Math.abs(beforeCarry - loaded.carryCapacity()) < 1e-3);

        check("生存状态恢复", loaded.survival().hunger() == 80 && loaded.survival().thirst() == 60
                && loaded.survival().temperature() == 50 && loaded.survival().sanity() == 90);
        check("背包恢复", loaded.inventory().count("__smoke_sword__") == 2
                && loaded.inventory().count("__smoke_bread__") == 5);
        check("NPC好感度/全局标记/步数/时间恢复",
                gs.npcAffinities().get("npc_smith") == 25
                        && Boolean.TRUE.equals(gs.worldFlags().get("met_elder"))
                        && gs.stepCount() == 42
                        && gs.gameDay() == 3 && gs.gameHour() == 14);

        // P4 验收：世界/迷雾/时钟重建
        check("时钟恢复（第3天 14点）", time.day() == 3 && time.hour() == 14);
        check("地图层级恢复为地表", gs.map().currentLayer() == MapLayer.SURFACE);
        List<FogRow> afterFog = gs.map().currentFog().exportRows();
        check("迷雾逐行恢复一致", afterFog.equals(beforeFog));
        check("玩家位置处迷雾可见", gs.map().currentFog().isVisible(25, 25));
        check("远处格子仍为未探索", gs.map().currentFog().get(49, 49) == com.canglan.world.CellState.UNEXPLORED);

        // 队友重建（两遍好感度矩阵）
        check("队友数量一致", gs.companions().size() == 2);
        if (gs.companions().size() == 2) {
            Unit c = gs.companions().get(0);
            Unit c2 = gs.companions().get(1);
            check("队友身份与好感度恢复", "小伴".equals(c.name()) && c.affinity() == 30
                    && c.currentRace() != null && "human".equals(c.currentRace().id()));
            check("队友间好感度按名字恢复", c.getAllyAffinity(c2) == 10);
        }

        // ========== 自动存档触发器 ==========
        AutoSaveTrigger trigger = new AutoSaveTrigger(bus, sm,
                () -> SaveManager.capture(player, null, null, null, null, null,
                        1L, "自动", 1, 6, null, null, null, null, null, 0, null));
        bus.emit(EventTypes.BATTLE_END);
        check("BATTLE_END 触发自动存档(slot 0)", sm.load(AutoSaveTrigger.AUTO_SLOT) != null);

        // ========== 死亡处理（轻度惩罚） ==========
        player.setGold(100);
        player.setExp(100);
        DeathHandler dh = new DeathHandler(DeathMode.PENALTY, sm);
        DeathOutcome outcome = dh.handleDeath(player);
        check("PENALTY 复活且金币减半/经验×0.8",
                outcome == DeathOutcome.REVIVED && player.gold() == 50 && player.exp() == 80
                        && !player.isDead() && player.stats().hp() > 0);

        // ========== 删档 ==========
        check("删档成功", sm.delete(1) && sm.load(1) == null);

        System.out.println();
        System.out.println("通过: " + passed + "  失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

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

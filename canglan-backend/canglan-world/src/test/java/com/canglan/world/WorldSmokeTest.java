package com.canglan.world;

import java.util.stream.Collectors;

import java.nio.file.Path;
import java.util.List;

import com.canglan.core.eventbus.EventBusImpl;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.core.tag.TagFactory;
import com.canglan.data.bootstrap.Registries;
import com.canglan.data.bootstrap.RegistryInitializer;
import com.canglan.data.buff.BuffFactory;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.unit.Unit;
import com.canglan.world.unit.UnitRole;

/**
 * P4 冒烟验证：GameTime/FogOfWar/WorldMap/WorldPopulator/SurvivalManager（无外部测试框架）。
 * 验收：固定种子布局一致、迷雾更新正确。
 * 用法: java com.canglan.world.WorldSmokeTest &lt;dataDir&gt;
 */
public final class WorldSmokeTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");
        System.out.println("数据目录: " + dataDir.toAbsolutePath());

        Registries r = RegistryInitializer.initialize(dataDir);
        check("世界内容注册表已加载（怪物/资源/NPC）",
                r.monsters.size() > 0 && r.resources.size() > 0 && r.npcs.size() > 0);

        EventBusImpl bus = new EventBusImpl();
        EffectEngine engine = new EffectEngine(new BuffFactory(r.buffs));
        TagFactory tagFactory = new TagFactory(r.tags);

        // ========== GameTime ==========
        GameTime time = new GameTime();
        check("开局第1天清晨6点/黎明/春季",
                time.day() == 1 && time.hour() == 6
                        && time.phase() == DayPhase.DAWN && time.season() == Season.SPRING);
        check("黎明算夜间（遭遇/理智语义）", time.isNight());
        time.advance(18);   // 6 → 24 → 第2天0点
        check("推进18小时跨天", time.day() == 2 && time.hour() == 0 && time.phase() == DayPhase.NIGHT);
        time.restore(8, 12);
        check("第8天为夏季", time.season() == Season.SUMMER);
        time.restore(15, 19);
        check("第15天为秋季/黄昏", time.season() == Season.AUTUMN && time.phase() == DayPhase.DUSK);
        time.restore(22, 3);
        check("第22天为冬季/夜间", time.season() == Season.WINTER && time.phase() == DayPhase.NIGHT);
        time.advanceTo(DayPhase.DAWN);
        check("advanceTo 黎明", time.phase() == DayPhase.DAWN);
        check("时间栏展示格式", time.display().startsWith("第22天 · 冬 · 黎明"));

        // ========== FogOfWar ==========
        Unit scout = new Unit("斥候", UnitRole.PLAYER, tagFactory, engine, bus, r.items);
        FogOfWar fog = new FogOfWar(21, 21, 5);
        scout.setWorldPos(new MapPos(10, 10));
        fog.update(scout);
        check("圆形视野：中心可见", fog.isVisible(10, 10));
        check("圆形视野：边界(5,0)在圆内可见", fog.isVisible(15, 10) && fog.isVisible(10, 15));
        check("圆形视野：对角(4,4)超半径不可见", !fog.isVisible(14, 14));
        check("圆形视野：圈外未探索", fog.get(0, 0) == CellState.UNEXPLORED);

        // 移动 8 格 → 旧视野衰减为已探索，新视野可见
        scout.setWorldPos(new MapPos(18, 10));
        fog.decayAfterMove(scout);
        fog.update(scout);
        check("离开后旧中心衰减为已探索", fog.get(10, 10) == CellState.EXPLORED);
        check("新位置可见", fog.isVisible(18, 10));

        // 导出/导入往返一致
        List<FogRow> exported = fog.exportRows();
        FogOfWar fog2 = new FogOfWar(21, 21, 5);
        fog2.importRows(exported);
        check("迷雾导出/导入往返一致", fog2.exportRows().equals(exported));
        check("每行长度=宽度", exported.size() == 21 && exported.get(0).states().length() == 21);

        // ========== WorldMap ==========
        WorldMap map = new WorldMap(50, 50);
        check("每层视野不同（地表5/地下3/天空8）",
                map.getFog(MapLayer.SURFACE).visionRange() == 5
                        && map.getFog(MapLayer.UNDERGROUND).visionRange() == 3
                        && map.getFog(MapLayer.SKY).visionRange() == 8);
        check("层间迷雾独立", map.currentFog() != map.getFog(MapLayer.UNDERGROUND));
        map.setBiome(5, 5, BiomeType.DESERT);
        check("biome 设置/查询", map.currentBiome(new MapPos(5, 5)) == BiomeType.DESERT);
        check("越界查询回退平原", map.currentBiome(new MapPos(999, 0)) == BiomeType.PLAINS);
        map.switchLayer(MapLayer.SKY);
        check("切层后 currentFog 跟随", map.currentFog() == map.getFog(MapLayer.SKY));
        map.switchLayer(MapLayer.SURFACE);

        // ========== WorldPopulator 固定种子布局一致 ==========
        List<String> npcIds = r.npcs.getAll().stream().map(d -> d.id()).collect(Collectors.toList());
        List<String> monsterIds = r.monsters.getAll().stream().map(t -> t.id()).collect(Collectors.toList());
        List<String> resourceIds = r.resources.getAll().stream().map(res -> res.id()).collect(Collectors.toList());

        WorldMap mapA = new WorldMap(50, 50);
        WorldMap mapB = new WorldMap(50, 50);
        WorldPopulator.populate(mapA, npcIds, monsterIds, resourceIds);
        WorldPopulator.populate(mapB, npcIds, monsterIds, resourceIds);
        check("固定种子布局跨次一致", mapA.features().equals(mapB.features()));

        MapPos village = new MapPos(25, 25);
        check("地形物总数符合公式（2+NPC+2M+2+3R）",
                mapA.features().size() == 2 + npcIds.size() + monsterIds.size() * 2 + 2
                        + resourceIds.size() * 3);
        check("布告板在村庄内", mapA.features().stream().anyMatch(f ->
                "布告板".equals(f.id()) && f.type() == FeatureType.BUILDING
                        && f.pos().distanceTo(village) <= 2));
        check("NPC 刷新点全在村庄半径2格内", mapA.features().stream()
                .filter(f -> f.type() == FeatureType.NPC_SPAWN)
                .allMatch(f -> f.pos().distanceTo(village) <= 2.5));
        check("怪物刷新点在距村 5.5~22.5 环带", mapA.features().stream()
                .filter(f -> f.type() == FeatureType.MONSTER_SPAWN)
                .allMatch(f -> f.pos().distanceTo(village) >= 5.5 && f.pos().distanceTo(village) <= 22.5));
        check("全部地形物在地图边界内", mapA.features().stream().allMatch(f ->
                f.pos().x() >= 0 && f.pos().x() < 50 && f.pos().y() >= 0 && f.pos().y() < 50));
        check("findNearby 村内能查到布告板", !mapA.findNearby(village, 2).isEmpty());

        // ========== SurvivalManager 统筹 ==========
        Unit player = new Unit("冒险者", UnitRole.PLAYER, tagFactory, engine, bus, r.items);
        player.setWorldPos(new MapPos(25, 25));
        int[] moved = {0};
        bus.subscribe(EventTypes.PLAYER_MOVED, e -> moved[0]++);
        SurvivalManager survivalManager = new SurvivalManager(bus);
        GameTime clock = new GameTime();

        int hunger0 = player.survival().hunger();
        int thirst0 = player.survival().thirst();
        survivalManager.onPlayerMove(player, mapA, clock);
        check("移动后 PLAYER_MOVED 发射", moved[0] == 1);
        check("移动消耗饱食/水分（春季平原）",
                player.survival().hunger() == hunger0 - 1 && player.survival().thirst() == thirst0 - 2);
        check("移动后脚下迷雾可见", mapA.currentFog().isVisible(25, 25));

        // 沙漠 + 夏季消耗加剧
        mapA.setBiome(30, 25, BiomeType.DESERT);
        player.setWorldPos(new MapPos(30, 25));
        clock.restore(8, 12);   // 第8天=夏季
        int thirst1 = player.survival().thirst();
        survivalManager.onPlayerMove(player, mapA, clock);
        check("沙漠夏季耗水 9（6×1.5）", player.survival().thirst() == thirst1 - 9);

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

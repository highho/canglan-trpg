package com.canglan.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.canglan.world.DifficultyMode;
import com.canglan.world.FeatureType;
import com.canglan.world.MapPos;
import com.canglan.world.TerrainFeature;

/**
 * FullFlowSmokeTest — P9 验收（§10.2.1）：核心玩法全链路深度冒烟。
 *
 * 建档（锻造师）→ 寻矿采集 → 回家制造 → 寻怪战斗 → 回村接/完成委托 → 存档/读档一致性。
 * 与 ApiSmokeTest（指令面/HTTP 面）互补：本测试断言玩法结果（收获/产出/结算/回档）。
 *
 * 用法：java com.canglan.api.FullFlowSmokeTest &lt;dataDir&gt; [saveDir=build-fullflow-test]
 */
public final class FullFlowSmokeTest {

    private static int pass, fail;

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "../data");
        Path saveDir = Path.of(args.length > 1 ? args[1] : "build-fullflow-test");
        if (Files.exists(saveDir)) {
            try (var walk = Files.walk(saveDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(saveDir);

        GameSession s = new GameSession(dataDir, saveDir, new Random(42), DifficultyMode.NORMAL);

        // ========== 1. 建档：锻造师（自带[锻造][矿工]标签，制造链路可达） ==========
        System.out.println("== 1. 建档 ==");
        s.execute("创建 全链路测试者");
        s.execute("人类");
        s.execute("锻造师");
        CommandResult created = s.execute("1");
        check("建档完成", Boolean.TRUE.equals(created.hud().get("hasPlayer")) && noError(created));

        MapPos village = GameSession.VILLAGE;

        // ========== 2. 采集：找最近矿脉 → 走过去 → 收获矿石 ==========
        System.out.println("== 2. 采集 ==");
        TerrainFeature ore = nearest(s, village, FeatureType.GATHER_POINT, "iron_ore", "copper_ore");
        check("世界存在铁矿/铜矿采集点", ore != null);
        if (ore != null) {
            walkTo(s, ore.pos().x(), ore.pos().y());
            check("已抵达矿脉附近",
                    s.player.worldPos().distanceTo(ore.pos()) <= GameSession.NEARBY_RANGE);
            CommandResult gather = s.execute("采集 " + ore.id());
            check("采集成功（收获矿石）", gather.text().contains("收获") && noError(gather));
        }

        // ========== 3. 制造：回家园 → 冶炼金属锭 ==========
        System.out.println("== 3. 制造 ==");
        walkTo(s, village.x(), village.y());
        String craftName = ore != null && ore.id().equals("copper_ore") ? "铜锭" : "铁锭";
        CommandResult craft = s.execute("制造 冶炼" + craftName.replace("锭", "") + "锭");
        check("制造成功（冶炼" + craftName + "）", craft.text().contains("完成") && noError(craft));

        // ========== 4. 战斗：找最近怪物刷新点 → 走过去 → 遭遇战结算 ==========
        System.out.println("== 4. 战斗 ==");
        TerrainFeature spawn = nearest(s, s.player.worldPos(), FeatureType.MONSTER_SPAWN);
        check("世界存在怪物刷新点", spawn != null);
        if (spawn != null) {
            walkTo(s, spawn.pos().x(), spawn.pos().y());
            String monsterName = s.registries.monsters.tryGet(spawn.id()).name();
            CommandResult fight = s.execute("攻击 " + monsterName);
            boolean resolved = fight.text().contains("鏖战")
                    && (fight.text().contains("胜利") || fight.text().contains("不敌"));
            check("遭遇战完整结算（胜或负）", resolved && noError(fight));
            s.execute("吃 旅行干粮");   // 战后补给，保证后续路程生存值安全
            s.execute("喝水");
        }

        // ========== 5. 任务：回村布告板 → 列表可见 → 结算委托拿报酬 ==========
        System.out.println("== 5. 任务 ==");
        walkTo(s, village.x(), village.y());
        CommandResult questList = s.execute("任务");
        check("布告板委托列表可见", questList.text().contains("委托") && noError(questList));
        int goldBefore = s.player.gold();
        CommandResult questDone = s.execute("完成 初次狩猎");
        check("委托【初次狩猎】完成", questDone.text().contains("完成！") && noError(questDone));
        check("委托报酬金币 +50", s.player.gold() == goldBefore + 50);

        // ========== 6. 存档/读档：源头数据一致性 ==========
        System.out.println("== 6. 存档/读档 ==");
        s.execute("存档");
        int savedGold = s.player.gold();
        MapPos savedPos = s.player.worldPos();
        s.execute("东");   // 存档后再移动，制造状态差异
        CommandResult loaded = s.execute("读档");
        check("读档成功", noError(loaded));
        check("读档后金币一致", s.player.gold() == savedGold);
        check("读档后坐标一致",
                s.player.worldPos().x() == savedPos.x() && s.player.worldPos().y() == savedPos.y());

        System.out.println();
        System.out.println("通过: " + pass + "  失败: " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 从 features 里找最近（曼哈顿距离）的指定类型要素，可按 id 白名单过滤。 */
    private static TerrainFeature nearest(GameSession s, MapPos from, FeatureType type, String... ids) {
        TerrainFeature best = null;
        int bestDist = Integer.MAX_VALUE;
        List<TerrainFeature> all = s.map.features();
        for (TerrainFeature f : all) {
            if (f.type() != type) continue;
            if (ids.length > 0) {
                boolean hit = false;
                for (String id : ids) if (f.id().equals(id)) { hit = true; break; }
                if (!hit) continue;
            }
            int d = Math.abs(f.pos().x() - from.x()) + Math.abs(f.pos().y() - from.y());
            if (d < bestDist) { bestDist = d; best = f; }
        }
        return best;
    }

    /** 曼哈顿式走向目标（长途自动补给，防止生存值见底）。 */
    private static void walkTo(GameSession s, int tx, int ty) {
        for (int i = 0; i < 120; i++) {
            int x = s.player.worldPos().x(), y = s.player.worldPos().y();
            if (x == tx && y == ty) return;
            if (i > 0 && i % 20 == 0) { s.execute("吃 旅行干粮"); s.execute("喝水"); }
            if (x < tx) s.execute("东");
            else if (x > tx) s.execute("西");
            else if (y < ty) s.execute("南");
            else s.execute("北");
        }
    }

    private static boolean noError(CommandResult r) {
        return r.lines().stream().noneMatch(l -> l.kind() == NarrationKind.ERROR);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }
}

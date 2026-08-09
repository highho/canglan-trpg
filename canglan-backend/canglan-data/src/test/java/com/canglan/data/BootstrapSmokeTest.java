package com.canglan.data;

import java.nio.file.Path;
import java.util.Set;

import com.canglan.core.eventbus.EventBusImpl;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;
import com.canglan.data.bootstrap.Registries;
import com.canglan.data.bootstrap.RegistryInitializer;

/**
 * Bootstrap 冒烟验证（无外部测试框架）：
 * 加载真实 data/*.json，断言注册表规模与条件 DSL / 图引擎关键行为。
 * 用法: java com.canglan.data.BootstrapSmokeTest <dataDir>
 */
public final class BootstrapSmokeTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");
        System.out.println("数据目录: " + dataDir.toAbsolutePath());

        Registries r = RegistryInitializer.initialize(dataDir);

        check("标签注册表非空", r.tags.size() > 0);
        check("物品注册表非空", r.items.size() > 0);
        System.out.println("tags=" + r.tags.size() + " items=" + r.items.size()
                + " buffs=" + r.buffs.size() + " equips=" + r.equips.size()
                + " skills=" + r.skills.size()
                + " raceNodes=" + r.raceGraph.allNodes().size()
                + " classNodes=" + r.classGraph.allNodes().size()
                + " questNodes=" + r.questGraph.allNodes().size());
        check("Buff 注册表非空", r.buffs.size() > 0);
        check("三图均有节点",
                !r.raceGraph.allNodes().isEmpty()
                        && !r.classGraph.allNodes().isEmpty()
                        && !r.questGraph.allNodes().isEmpty());

        // 条件 DSL：HasTag 正/反例
        TagConditionParser parser = new TagConditionParser();
        TagCondition hasFire = parser.parse("HasTag(火焰)");
        check("HasTag(火焰) 命中", hasFire.evaluate(Set.of("火焰")));
        check("HasTag(火焰) 未命中", !hasFire.evaluate(Set.of("寒冰")));

        // 条件 DSL：层级后缀 LvN（tags.json 中"火焰" tier=2，确定性断言）
        TagCondition fireLv2 = parser.parse("HasTag(火焰Lv2)");
        check("HasTag(火焰Lv2) 在 tier=2 时命中", fireLv2.evaluate(Set.of("火焰")));
        TagCondition fireLv9 = parser.parse("HasTag(火焰Lv9)");
        check("HasTag(火焰Lv9) 在 tier=2 时未命中", !fireLv9.evaluate(Set.of("火焰")));

        // 条件 DSL：组合
        TagCondition combo = parser.parse("HasTag(神圣) AND HasTag(光明)");
        check("AND 组合命中", combo.evaluate(Set.of("神圣", "光明")));
        check("AND 组合未命中", !combo.evaluate(Set.of("神圣")));

        // 未注册物品容错登记
        check("getOrRegister 容错", r.items.getOrRegister("__smoke_unknown__") != null);

        // 图引擎：至少一条可用出边存在于某种族节点（无ctx仅条件过滤）
        boolean anyEdge = r.raceGraph.allNodes().stream()
                .anyMatch(n -> !n.outgoingEdges().isEmpty());
        check("种族图存在边", anyEdge);

        // EventBus：同步发射 + 智能 payload 映射 + 按属主清理
        EventBusImpl bus = new EventBusImpl();
        int[] received = {0};
        Object owner = new Object();
        bus.subscribeWithOwner("SMOKE_EVENT", evt -> received[0] += evt.getInt("amount", 0), owner);
        bus.emit("SMOKE_EVENT", 7);
        check("EventBus 数字负载映射 amount", received[0] == 7);
        bus.unsubscribeAll(owner);
        bus.emit("SMOKE_EVENT", 7);
        check("EventBus 按属主清理后不再接收", received[0] == 7);

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

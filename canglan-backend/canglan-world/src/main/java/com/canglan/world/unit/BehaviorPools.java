package com.canglan.world.unit;

import java.util.Map;

/** 预置行为池工厂：战斗四选项（攻击/防御/逃跑/呼叫增援）+ 社交行为（交易/任务/贿赂...）。对应 C# BehaviorPools。 */
public final class BehaviorPools {

    private BehaviorPools() {}

    public static BehaviorPool defaultCombatPool() {
        BehaviorPool pool = new BehaviorPool("combat", "战斗行为池");
        pool.add(new BehaviorOption("attack", "攻击", 50, Map.of(
                "PERSONALITY", Map.of("勇敢", 20, "懦弱", -10),
                "EMOTION", Map.of("愤怒", 30, "恐惧", -30))));
        pool.add(new BehaviorOption("defend", "防御", 30, Map.of(
                "PERSONALITY", Map.of("懦弱", 20, "勇敢", -10),
                "EMOTION", Map.of("恐惧", 20))));
        pool.add(new BehaviorOption("flee", "逃跑", 10, Map.of(
                "PERSONALITY", Map.of("懦弱", 40, "勇敢", -30),
                "EMOTION", Map.of("恐惧", 40))));
        pool.add(new BehaviorOption("call_help", "呼叫增援", 10, Map.of(
                "PERSONALITY", Map.of("狡猾", 20))));
        return pool;
    }

    public static BehaviorPool defaultSocialPool() {
        BehaviorPool pool = new BehaviorPool("social", "社交行为池");
        pool.add(new BehaviorOption("talk", "交谈", 50));
        pool.add(new BehaviorOption("trade", "交易", 30, Map.of(
                "IDENTITY", Map.of("商人", 30),
                "PERSONALITY", Map.of("贪婪", 20))));
        pool.add(new BehaviorOption("quest", "任务", 30, Map.of(
                "IDENTITY", Map.of("长老", 20, "守卫", 10))));
        pool.add(new BehaviorOption("accept_bribe", "接受贿赂", 20, Map.of(
                "PERSONALITY", Map.of("正直", -40, "贪婪", 30),
                "IDENTITY", Map.of("守卫", -20),
                "EMOTION", Map.of("愤怒", 10))));
        pool.add(new BehaviorOption("reject", "拒绝", 40, Map.of(
                "PERSONALITY", Map.of("正直", 30),
                "IDENTITY", Map.of("守卫", 20))));
        pool.add(new BehaviorOption("report", "举报", 15, Map.of(
                "PERSONALITY", Map.of("正直", 20),
                "IDENTITY", Map.of("守卫", 10))));
        pool.add(new BehaviorOption("surrender", "投降", 20, Map.of(
                "PERSONALITY", Map.of("懦弱", 40, "勇敢", -30),
                "EMOTION", Map.of("恐惧", 40))));
        pool.add(new BehaviorOption("fight_back", "反击", 40, Map.of(
                "PERSONALITY", Map.of("勇敢", 30, "懦弱", -20),
                "EMOTION", Map.of("愤怒", 30))));
        return pool;
    }
}

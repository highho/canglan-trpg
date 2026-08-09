package com.canglan.world;

import java.util.List;
import java.util.Random;

import com.canglan.core.effect.Operator;
import com.canglan.core.effect.StatMod;
import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;
import com.canglan.data.buff.Buff;
import com.canglan.data.buff.BuffDef;
import com.canglan.data.buff.BuffType;
import com.canglan.data.item.ItemDef;
import com.canglan.world.unit.Unit;

/**
 * SurvivalStats — 生存数值管理（饱食度/水分/体温/理智）。对应 C# SurvivalStats。
 * 临界值发射 *_CRITICAL 事件 → EmotionSystem 添加 [饥饿]/[干渴]/[冻伤] 标签；
 * 归零惩罚以永久Buff方式施加（无状态可重建，避免重复乘法污染）。
 */
public final class SurvivalStats {

    private final Unit owner;
    private final EventBus eventBus;
    private final Random rng;

    private int hunger = 100;        // 饱食度 0-100
    private int thirst = 100;        // 水分   0-100
    private int temperature = 100;   // 体温   0-100
    private int sanity = 100;        // 理智   0-100（开拓者式：夜晚/孤独/惊悚事件消耗）

    /** 理智临界（≤此值挂[疯癫]标签，行为错乱）。 */
    public static final int SANITY_DANGER = 30;

    public int hunger() { return hunger; }
    public int thirst() { return thirst; }
    public int temperature() { return temperature; }
    public int sanity() { return sanity; }

    public SurvivalStats(Unit owner, EventBus bus) {
        this(owner, bus, null);
    }

    public SurvivalStats(Unit owner, EventBus bus, Random rng) {
        this.owner = owner;
        this.eventBus = bus;
        this.rng = rng != null ? rng : new Random();
    }

    /** 读档注入存档值（跳过构造函数初始值）。 */
    public void restore(int hunger, int thirst, int temperature) {
        restore(hunger, thirst, temperature, 100);
    }

    public void restore(int hunger, int thirst, int temperature, int sanity) {
        this.hunger = clamp(hunger);
        this.thirst = clamp(thirst);
        this.temperature = clamp(temperature);
        this.sanity = clamp(sanity);
    }

    /** 恢复理智（家园休息/白天/同伴陪伴）。 */
    public void restoreSanity(int amount) {
        sanity = Math.min(100, sanity + Math.abs(amount));
        if (sanity > SANITY_DANGER) owner.emotion().setSurvivalEmotion("疯癫", false);
        owner.recalculateTags();
    }

    /**
     * 消耗理智（夜晚赶路/孤身一人在荒野/惊悚遭遇）。
     * 夜间基础-2，难度倍率放大；≤30 挂[疯癫]标签（ATK下降、说话混乱）。
     */
    public void drainSanity(int amount, double difficultyMul) {
        if (amount <= 0) return;
        sanity = Math.max(0, sanity - (int) Math.round(amount * difficultyMul));
        if (sanity <= SANITY_DANGER) {
            owner.emotion().setSurvivalEmotion("疯癫", true);
            owner.recalculateTags();
        }
    }

    /**
     * 每次大地图移动或回合结束时调用。
     * season 影响消耗：夏炎热多耗水（×1.5），冬严寒多耗热量（体温低起点），春/秋最温和。
     * consumeMul 来自难度（困难模式消耗更快）。
     */
    public void tick(BiomeType biome, Season season, double consumeMul) {
        double seasonMul = switch (season) {
            case SUMMER -> 1.5;   // 夏：多喝水
            case WINTER -> 1.3;   // 冬：多耗热量（体温再降）
            default -> 1.0;
        };
        double mul = consumeMul * seasonMul;
        hunger = Math.max(0, hunger - (int) Math.round(1 * mul));
        thirst = Math.max(0, thirst - (int) Math.round((biome == BiomeType.DESERT ? 6 : 2) * mul));
        temperature = biome.baseTemperature() - (season == Season.WINTER ? 15 : 0);   // 寒冬体温更低

        checkCritical(EventTypes.HUNGER_CRITICAL, hunger, 20, "饥饿");
        checkCritical(EventTypes.THIRST_CRITICAL, thirst, 20, "干渴");
        checkCritical(EventTypes.TEMP_CRITICAL, temperature, 20, "冻伤");
    }

    /** 临界值 → 发射事件 + 挂接生存情感标签（≤20 获得，>20 移除）。 */
    private void checkCritical(String eventType, int value, int threshold, String tagId) {
        if (value <= threshold) {
            eventBus.emit(eventType, owner, value);
            owner.emotion().setSurvivalEmotion(tagId, true);
        } else if (value > 20) {
            owner.emotion().setSurvivalEmotion(tagId, false);
        }
    }

    /** 消耗食物恢复饱食度（营养值来自 ItemDef.nutrition）。 */
    public void consume(ItemDef food) {
        hunger = Math.min(100, hunger + food.nutrition());
        if (hunger > 20) owner.emotion().setSurvivalEmotion("饥饿", false);
        owner.recalculateTags();
    }

    /** 饮水恢复水分。 */
    public void drink(int amount) {
        thirst = Math.min(100, thirst + amount);
        if (thirst > 20) owner.emotion().setSurvivalEmotion("干渴", false);
        owner.recalculateTags();
    }

    /**
     * 归零惩罚（以永久Buff施加，幂等）：
     * 饱食度0 → HP上限×0.5；水分0 → 移动距离×0.5 + 10%概率眩晕；体温0 → 每回合扣5HP。
     */
    public void applyPenalties() {
        if (hunger <= 0 && !owner.buffManager().hasBuff("starvation")) {
            owner.buffManager().addBuff(new Buff(new BuffDef("starvation", "饥饿虚弱",
                    BuffType.PERMANENT, 9999,
                    List.of(new StatMod("HP", Operator.MULTIPLY, 0.5f)), false, 1)));
        }
        if (thirst <= 0) {
            if (!owner.buffManager().hasBuff("dehydration")) {
                owner.buffManager().addBuff(new Buff(new BuffDef("dehydration", "脱水",
                        BuffType.PERMANENT, 9999,
                        List.of(new StatMod("MOVE_RANGE", Operator.MULTIPLY, 0.5f)), false, 1)));
            }
            if (rng.nextDouble() < 0.1 && !owner.buffManager().hasBuff("stun")) {
                owner.buffManager().addBuff(new Buff(new BuffDef("stun", "眩晕",
                        BuffType.TEMPORARY, 1, List.of(), false, 1)));   // 1回合眩晕
            }
        }
        if (temperature <= 0) owner.takeDamage(5, null, eventBus);   // 每回合扣5HP
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }
}

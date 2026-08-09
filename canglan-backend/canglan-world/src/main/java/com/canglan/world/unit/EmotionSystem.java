package com.canglan.world.unit;

import com.canglan.core.eventbus.Event;
import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;

/**
 * EmotionSystem — 情感动态管理。对应 C# EmotionSystem。
 * 四维数值（恐惧/愤怒/悲伤/喜悦）0-100，> 50 阈值才成为活跃 EMOTION 标签。
 * 跷跷板衰减：提升一种情感会压制对立情感。
 * 订阅属主 = 本实例（不会被 Unit.recalculateTags 的 unsubscribeAll(this) 清理）。
 */
public final class EmotionSystem {

    public static final String FEAR = "恐惧";
    public static final String ANGER = "愤怒";
    public static final String SORROW = "悲伤";
    public static final String JOY = "喜悦";

    private final Unit owner;
    private int fearLevel;      // 0-100
    private int angerLevel;     // 0-100
    private int sorrowLevel;    // 0-100
    private int joyLevel;       // 0-100
    private final java.util.HashSet<String> survivalEmotions = new java.util.HashSet<>();  // 生存状态标签（饥饿/干渴/冻伤）

    public int fearLevel() { return fearLevel; }
    public int angerLevel() { return angerLevel; }
    public int sorrowLevel() { return sorrowLevel; }
    public int joyLevel() { return joyLevel; }

    public EmotionSystem(Unit owner, EventBus bus) {
        this.owner = owner;
        registerListeners(bus);
    }

    private void registerListeners(EventBus bus) {
        bus.subscribeWithOwner(EventTypes.DAMAGE_CRIT, e -> {
            if (e.get("target") == owner) applyEmotion(ANGER, 40);
        }, this);
        bus.subscribeWithOwner(EventTypes.ALLY_DEATH, e -> applyEmotion(SORROW, 60), this);
        bus.subscribeWithOwner(EventTypes.SURROUNDED, e -> {
            if (e.get("target") == owner) applyEmotion(FEAR, 30);
        }, this);
        bus.subscribeWithOwner(EventTypes.ENEMY_KILLED, e -> {
            if (e.source() == owner || e.get("target") == owner) applyEmotion(JOY, 20);
        }, this);
        bus.subscribeWithOwner(EventTypes.HP_BELOW_30, e -> {
            Object unit = e.get("unit");
            if (e.get("target") == owner || unit == owner) applyEmotion(FEAR, 50);
        }, this);
    }

    /** 提升情感强度并跷跷板衰减对立情感。调用方负责 recalculateTags。 */
    public void applyEmotion(String emotionId, int intensity) {
        switch (emotionId) {
            case FEAR -> fearLevel = Math.min(100, fearLevel + intensity);
            case ANGER -> angerLevel = Math.min(100, angerLevel + intensity);
            case SORROW -> sorrowLevel = Math.min(100, sorrowLevel + intensity);
            case JOY -> joyLevel = Math.min(100, joyLevel + intensity);
            default -> { }
        }
        decayOpposing(emotionId, intensity / 2);
    }

    private void decayOpposing(String emotionId, int amount) {
        switch (emotionId) {
            case ANGER -> joyLevel = Math.max(0, joyLevel - amount);
            case JOY -> angerLevel = Math.max(0, angerLevel - amount);
            case FEAR -> angerLevel = Math.max(0, angerLevel - amount / 2);
            default -> { }
        }
    }

    /** 每回合自然衰减，返回是否有活跃情感数量变化（true → 需要 recalculateTags）。 */
    public boolean tickDecay() {
        int before = activeEmotionIds().size();
        fearLevel = Math.max(0, fearLevel - 5);
        angerLevel = Math.max(0, angerLevel - 5);
        sorrowLevel = Math.max(0, sorrowLevel - 8);
        joyLevel = Math.max(0, joyLevel - 5);
        return activeEmotionIds().size() != before;
    }

    /** 生存状态标签挂接/移除（饥饿/干渴/冻伤，由生存系统临界值判定驱动）。 */
    public void setSurvivalEmotion(String tagId, boolean active) {
        if (active) survivalEmotions.add(tagId);
        else survivalEmotions.remove(tagId);
    }

    /** > 50 阈值的情感才成为活跃 EMOTION 标签（含生存状态标签）。 */
    public java.util.HashSet<String> activeEmotionIds() {
        java.util.HashSet<String> ids = new java.util.HashSet<>(survivalEmotions);
        if (fearLevel > 50) ids.add(FEAR);
        if (angerLevel > 50) ids.add(ANGER);
        if (sorrowLevel > 50) ids.add(SORROW);
        if (joyLevel > 50) ids.add(JOY);
        return ids;
    }

    /** 读档恢复情感四维。 */
    public void restore(int fear, int anger, int sorrow, int joy) {
        fearLevel = clamp(fear);
        angerLevel = clamp(anger);
        sorrowLevel = clamp(sorrow);
        joyLevel = clamp(joy);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }
}

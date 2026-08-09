package com.canglan.data.buff;

import java.util.List;

import com.canglan.core.effect.EffectDef;
import com.canglan.core.effect.TriggerDef;

/**
 * BuffFactory — 从 BuffDef/装备/触发器 创建 Buff。对应 C# BuffFactory。
 * 显式注入 BuffRegistry（替代 C# 静态 Instance）。
 */
public final class BuffFactory {

    private final BuffRegistry registry;

    public BuffFactory(BuffRegistry registry) {
        this.registry = registry;
    }

    public Buff create(String buffId) {
        return new Buff(registry.get(buffId));
    }

    /** 标签 TRIGGER 效果创建 Buff。 */
    public Buff createFromRegistry(String buffId, int duration) {
        Buff buff = new Buff(registry.get(buffId));
        if (duration >= 0) buff.setRemainingDuration(duration);
        return buff;
    }

    /** 从装备创建永久Buff（装备不进 TagSet，走 Buff 系统）。 */
    public static Buff createFromEquip(String equipId, String equipName, List<EffectDef> effects) {
        return new Buff(new BuffDef(equipId + "_buff", equipName, BuffType.PERMANENT, -1, effects, false, 1));
    }

    /** 从标签TRIGGER创建触发Buff。 */
    public static Buff createFromTrigger(TriggerDef trigger) {
        Object b = trigger.params().get("buffId");
        Object d = trigger.params().get("duration");
        return new Buff(new BuffDef(
                b != null ? b.toString() : "trigger_buff",
                "trigger_buff",
                BuffType.TRIGGERED,
                d instanceof Number n ? n.intValue() : 3,
                List.of(),
                false, 1));
    }

    /** 套装Buff（N件套效果，永久生效）。 */
    public static Buff createFromSetBonus(String setId, String bonusName, List<EffectDef> effects) {
        return new Buff(new BuffDef(setId + "_bonus", bonusName, BuffType.PERMANENT, -1, effects, false, 1));
    }

    /** 场景Buff（进入场景时附加）。 */
    public static Buff createScene(String id, String name, List<EffectDef> effects) {
        return new Buff(new BuffDef(id, name, BuffType.SCENE, -1, effects, false, 1));
    }
}

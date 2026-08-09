package com.canglan.data.skill;

import java.util.List;

import com.canglan.core.effect.EffectDef;
import com.canglan.core.tag.TagCondition;

/**
 * Skill — 战斗中的核心行动单元。对应 C# Skill。
 */
public final class Skill {
    private final String id;
    private final String name;
    private final SkillType type;
    private final int cooldown;           // 冷却回合数
    private int currentCooldown;          // 当前剩余冷却
    private final TargetPattern targetPattern;
    private final List<EffectDef> effects;
    private final TagCondition unlockCondition;
    private final int range;              // 作用距离（格子数）
    private final int baseDamage;
    private final DamageType damageType;

    public Skill(String id, String name, SkillType type, int cooldown, TargetPattern targetPattern,
                 List<EffectDef> effects, TagCondition unlockCondition, int range,
                 int baseDamage, DamageType damageType) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.cooldown = cooldown;
        this.targetPattern = targetPattern;
        this.effects = effects;
        this.unlockCondition = unlockCondition;
        this.range = range;
        this.baseDamage = baseDamage;
        this.damageType = damageType;
    }

    public String id() { return id; }
    public String name() { return name; }
    public SkillType type() { return type; }
    public int cooldown() { return cooldown; }
    public int currentCooldown() { return currentCooldown; }
    public TargetPattern targetPattern() { return targetPattern; }
    public List<EffectDef> effects() { return effects; }
    public TagCondition unlockCondition() { return unlockCondition; }
    public int range() { return range; }
    public int baseDamage() { return baseDamage; }
    public DamageType damageType() { return damageType; }

    public boolean isReady() { return currentCooldown == 0; }

    public void tickCooldown() {
        if (currentCooldown > 0) currentCooldown--;
    }

    public void use() { currentCooldown = cooldown; }

    @Override
    public String toString() { return name + "(" + id + ") CD:" + currentCooldown + "/" + cooldown; }
}

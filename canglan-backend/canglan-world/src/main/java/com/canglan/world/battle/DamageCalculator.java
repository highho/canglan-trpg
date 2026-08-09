package com.canglan.world.battle;

import java.util.Random;

import com.canglan.core.effect.DamageMod;
import com.canglan.core.effect.EffectDef;
import com.canglan.core.tag.Tag;
import com.canglan.data.skill.DamageType;
import com.canglan.data.skill.Skill;
import com.canglan.world.unit.Unit;

/**
 * DamageCalculator — 五层伤害计算。
 * 第一层 标签效果（ATK 标签快照）→ 第二层 Buff效果 → 已由 Unit.getStat 统一合并（同类效果叠加）；
 * 第三层 站位修正（前排+15%）；第四层 克制关系（标签/Buff 的 DAMAGE_MOD）；第五层 防御减免。
 * 对应 C# DamageCalculator。
 */
public final class DamageCalculator {

    private DamageCalculator() {}

    /** 伤害结果：最终伤害 + 是否暴击。 */
    public record DamageResult(float damage, boolean crit) {}

    /** 普攻伤害。返回最终伤害与是否暴击（暴击 ×1.5）。 */
    public static DamageResult calculate(Unit attacker, Unit defender, GridSystem grid, Random rng) {
        // 第一层 + 第二层：基础ATK + 标签快照 + Buff快照（Unit.getStat 统一查询，同类效果叠加）
        float damage = attacker.atk();

        // 第三层：站位修正
        damage *= GridSystem.getPositionModifier(attacker.gridPos());

        // 第四层：克制关系（标签间 DAMAGE_MOD + Buff DAMAGE_MOD）
        damage *= getTagDamageModMultiplier(attacker, defender);
        damage *= attacker.buffManager().getDamageModMultiplier(defender);

        // 第五层：防御减免
        damage -= defender.def();

        // 暴击判定
        boolean crit = rng.nextDouble() < attacker.getStat("CRIT");
        if (crit) damage *= 1.5f;

        return new DamageResult(Math.max(1f, damage), crit);
    }

    /** 技能伤害（base = skill.baseDamage，其余层同上）。 */
    public static DamageResult calculateSkill(Unit attacker, Skill skill, Unit defender, GridSystem grid, Random rng) {
        float damage = skill.baseDamage() + attacker.atk() * 0.5f;
        damage *= GridSystem.getPositionModifier(attacker.gridPos());
        damage *= getTagDamageModMultiplier(attacker, defender);
        damage *= attacker.buffManager().getDamageModMultiplier(defender);
        if (skill.damageType() != DamageType.TRUE)
            damage -= defender.def();

        boolean crit = rng.nextDouble() < attacker.getStat("CRIT");
        if (crit) damage *= 1.5f;
        return new DamageResult(Math.max(1f, damage), crit);
    }

    /** 第四层：攻击方活跃标签中的 DAMAGE_MOD 效果对防御方的倍率。 */
    private static float getTagDamageModMultiplier(Unit attacker, Unit defender) {
        float multiplier = 1f;
        for (Tag tag : attacker.activeTags()) {
            for (EffectDef effect : tag.effects()) {
                if (effect instanceof DamageMod dm && dm.matches(defender)) {
                    multiplier = switch (dm.operator()) {
                        case ADD -> multiplier + dm.value();
                        case MULTIPLY -> multiplier * dm.value();
                        case SET -> dm.value();
                    };
                }
            }
        }
        return multiplier;
    }
}

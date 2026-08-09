package com.canglan.data.monster;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 怪物模板（monsters.json）。对应 C# MonsterTemplate。
 * 种族/人格标签进 Unit.traitTagIds（怪物无进化图身份）；
 * 掉落表/经验值结算时存 Unit.metadata。DropTable/MonsterFactory 留待 P5 战斗阶段。
 */
public record MonsterTemplate(
        String id,
        String name,
        Map<String, Float> baseStats,
        Set<String> raceTagIds,
        Set<String> personalityTagIds,
        List<String> behaviorPool,
        List<LootEntry> drops,
        int expReward,
        CombatRole combatRole,
        Map<String, Integer> specialSkills) {
}

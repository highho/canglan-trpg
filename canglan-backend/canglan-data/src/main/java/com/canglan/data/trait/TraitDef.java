package com.canglan.data.trait;

import java.util.Set;

/**
 * 特质定义（traits.json）。对应 C# TraitDef record。
 *
 * @param tagIds            特质赋予的标签集
 * @param startingGold      初始金币加成
 * @param raceRestriction   种族限制（按种族名），null=无限制
 * @param classRestriction  职业限制（按职业名），null=无限制
 */
public record TraitDef(
        String id,
        String name,
        Set<String> tagIds,
        int startingGold,
        String raceRestriction,
        String classRestriction,
        String description) {
}

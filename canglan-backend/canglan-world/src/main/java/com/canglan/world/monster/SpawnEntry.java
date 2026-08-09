package com.canglan.world.monster;

import com.canglan.data.monster.MonsterTemplate;

/** 刷怪条目。对应 C# SpawnEntry。 */
public record SpawnEntry(MonsterTemplate template, int minCount, int maxCount, float weight) {
}

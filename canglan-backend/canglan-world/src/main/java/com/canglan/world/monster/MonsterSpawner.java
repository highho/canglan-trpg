package com.canglan.world.monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.canglan.world.MapPos;
import com.canglan.world.WorldMap;
import com.canglan.world.unit.Unit;

/**
 * MonsterSpawner — 按区域配置刷怪：权重抽取条目 → 数量随机 → 区域内随机有效位置。
 * 对应 C# MonsterSpawner。
 */
public final class MonsterSpawner {

    private final MonsterFactory factory;
    private final WorldMap worldMap;
    private final Random rng;

    public MonsterSpawner(MonsterFactory factory, WorldMap worldMap, Random rng) {
        this.factory = factory;
        this.worldMap = worldMap;
        this.rng = rng != null ? rng : new Random();
    }

    public MonsterSpawner(MonsterFactory factory, WorldMap worldMap) {
        this(factory, worldMap, null);
    }

    /** 按区域配置刷怪，返回生成的怪物列表。 */
    public List<Unit> spawn(AreaConfig config) {
        List<Unit> spawned = new ArrayList<>();
        for (SpawnEntry entry : config.entries()) {
            int count = entry.minCount() + rng.nextInt(entry.maxCount() - entry.minCount() + 1);
            for (int i = 0; i < count; i++) {
                MapPos pos = findValidSpawnPos(config);
                if (pos == null) continue;
                Unit monster = factory.create(entry.template());
                monster.setWorldPos(pos);
                spawned.add(monster);
            }
        }
        return spawned;
    }

    /** 在区域内随机选有效位置（地图内即可；视野规避由上层战争迷雾决定）。 */
    private MapPos findValidSpawnPos(AreaConfig config) {
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = rng.nextInt(worldMap.width());
            int y = rng.nextInt(worldMap.height());
            if (worldMap.currentBiome(new MapPos(x, y)) == config.biome())
                return new MapPos(x, y);
        }
        return new MapPos(rng.nextInt(worldMap.width()), rng.nextInt(worldMap.height()));
    }
}

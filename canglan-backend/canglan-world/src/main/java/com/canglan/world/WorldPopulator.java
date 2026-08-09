package com.canglan.world;

import java.util.List;
import java.util.Random;

/**
 * WorldPopulator — 世界布置。对应 C# GameWorld.PopulateWorld。
 * 把 NPC（新手村）、怪物刷新点、采集点撒到地图上（固定种子，布局稳定）。
 * 行动选项按玩家坐标查询这些地形物，落地不能打全图的怪。
 */
public final class WorldPopulator {

    /** 固定布局种子（与 C# 一致：同种子布局稳定）。 */
    public static final long LAYOUT_SEED = 20260804L;

    private WorldPopulator() {}

    /** 用固定种子布置（布局跨次运行一致）。 */
    public static void populate(WorldMap map, List<String> npcIds,
                                List<String> monsterTemplateIds, List<String> resourceIds) {
        populate(map, npcIds, monsterTemplateIds, resourceIds, new Random(LAYOUT_SEED));
    }

    public static void populate(WorldMap map, List<String> npcIds,
                                List<String> monsterTemplateIds, List<String> resourceIds,
                                Random rng) {
        int cx = map.width() / 2;
        int cy = map.height() / 2;
        MapPos village = new MapPos(cx, cy);

        // 村庄设施：布告板（接委托）+ 水井（喝水点），村内即可使用
        map.addFeature(new TerrainFeature("布告板", clamp(map, cx + 1, cy + 1), FeatureType.BUILDING));
        map.addFeature(new TerrainFeature("spring", clamp(map, cx - 1, cy - 1), FeatureType.GATHER_POINT));

        // 新手村 NPC：村庄半径 2 格内（角度均匀分布，与随机数无关）
        int npcIdx = 0;
        int npcCount = Math.max(1, npcIds.size());
        for (String npcId : npcIds) {
            double angle = npcIdx * 2 * Math.PI / npcCount;
            int radius = npcIdx % 3 == 0 ? 1 : 2;
            map.addFeature(new TerrainFeature(npcId,
                    clamp(map, cx + (int) Math.round(Math.cos(angle) * radius),
                            cy + (int) Math.round(Math.sin(angle) * radius)),
                    FeatureType.NPC_SPAWN));
            npcIdx++;
        }

        // 怪物刷新点：每种怪 2 处，距村 8~22 格；另加 2 处郊外点（距村 6~9 格，村内不可见）
        for (String templateId : monsterTemplateIds) {
            for (int i = 0; i < 2; i++) {
                map.addFeature(new TerrainFeature(templateId,
                        scatterPos(map, village, rng, 8, 22), FeatureType.MONSTER_SPAWN));
            }
        }
        for (int i = 0; i < 2 && !monsterTemplateIds.isEmpty(); i++) {
            map.addFeature(new TerrainFeature(monsterTemplateIds.get(rng.nextInt(monsterTemplateIds.size())),
                    scatterPos(map, village, rng, 6, 9), FeatureType.MONSTER_SPAWN));
        }

        // 采集点：每种资源 2 处，距村 6~22 格；另加 1 处郊外点（距村 5~8 格，村内不可见）
        for (String resourceId : resourceIds) {
            for (int i = 0; i < 2; i++) {
                map.addFeature(new TerrainFeature(resourceId,
                        scatterPos(map, village, rng, 6, 22), FeatureType.GATHER_POINT));
            }
            map.addFeature(new TerrainFeature(resourceId,
                    scatterPos(map, village, rng, 5, 8), FeatureType.GATHER_POINT));
        }
    }

    /** 以锚点为中心、在 [minDist, maxDist] 环带内随机取一个地图内坐标。 */
    private static MapPos scatterPos(WorldMap map, MapPos anchor, Random rng, int minDist, int maxDist) {
        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double dist = minDist + rng.nextDouble() * (maxDist - minDist);
            MapPos pos = clamp(map, anchor.x() + (int) Math.round(Math.cos(angle) * dist),
                    anchor.y() + (int) Math.round(Math.sin(angle) * dist));
            double d = pos.distanceTo(anchor);
            if (d >= minDist - 0.5 && d <= maxDist + 0.5) return pos;
        }
        return clamp(map, anchor.x() + minDist, anchor.y());
    }

    private static MapPos clamp(WorldMap map, int x, int y) {
        return new MapPos(Math.max(0, Math.min(map.width() - 1, x)),
                Math.max(0, Math.min(map.height() - 1, y)));
    }
}

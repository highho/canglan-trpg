package com.canglan.data.npc;

import java.util.Map;
import java.util.Set;

import com.canglan.core.json.JsonValue;

/**
 * NPC 定义（npcs.json）。对应 C# NpcDef。
 * relation 存大写字符串（HOSTILE/FRIENDLY/ALLY/NEUTRAL）——
 * 数据层不依赖 canglan-world 的 RelationState，由世界层创建 Unit 时翻译。
 * dialogueTree 保留原始 JsonValue，由世界层 NpcFactory 惰性解析（P6）。
 */
public record NpcDef(
        String id,
        String name,
        Set<String> identityTags,
        Set<String> personalityTags,
        Map<String, Float> baseStats,
        String relation,
        Set<String> groups,
        JsonValue dialogueTree) {
}

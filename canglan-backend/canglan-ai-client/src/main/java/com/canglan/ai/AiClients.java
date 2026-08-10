package com.canglan.ai;

import java.nio.file.Path;
import java.util.Random;

/**
 * AiClients — 启动工厂（MIGRATION_PLAN §4.3 + AI 内嵌化）。
 * baseUrl 语义：
 * - 非空 → 外部 AI 服务（LangGraphHttpClient 探活）；探活失败降级内嵌管线
 * - 空/null → 内嵌管线 EmbeddedAiClient（二层记忆 + 规则引擎）
 * - "off"（忽略大小写）→ 显式禁用，纯 NullAiClient
 * LLM 供应商经 {@link AiProviderSettings} 运行时配置（前端设置页，本地/云端 OpenAI 兼容端点）。
 * 记忆持久化：saveDir/memories.json。
 */
public final class AiClients {

    private AiClients() {}

    public static AiClient connect(String baseUrl, Random rng, Path saveDir) {
        if (baseUrl != null && baseUrl.equalsIgnoreCase("off")) {
            return new NullAiClient(rng);
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            try {
                LangGraphHttpClient client = new LangGraphHttpClient(baseUrl, rng);
                if (client.probe()) return client;
            } catch (Throwable t) {
                // Android API < 33 无 java.net.http.HttpClient：类加载失败时降级内嵌管线
            }
        }
        return embedded(rng, saveDir);
    }

    private static AiClient embedded(Random rng, Path saveDir) {
        try {
            Path dir = saveDir != null ? saveDir : Path.of(".");
            NpcMemoryStore store = new NpcMemoryStore(dir.resolve("memories.json"));
            return new EmbeddedAiClient(rng, store);
        } catch (Throwable t) {
            return new NullAiClient(rng);   // 双保险：内嵌管线构造失败也不得影响启动
        }
    }
}

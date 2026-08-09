package com.canglan.ai;

import java.util.Random;

/**
 * AiClients — 启动工厂（MIGRATION_PLAN §4.3）。
 * 探活成功 → LangGraphHttpClient；失败 → NullAiClient，游戏照常启动。
 */
public final class AiClients {

    private AiClients() {}

    /** baseUrl 为空串/null 视为显式禁用 AI。 */
    public static AiClient connect(String baseUrl, Random rng) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new NullAiClient(rng);
        }
        LangGraphHttpClient client = new LangGraphHttpClient(baseUrl, rng);
        return client.probe() ? client : new NullAiClient(rng);
    }
}

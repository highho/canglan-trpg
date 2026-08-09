package com.canglan.ai;

import java.util.Random;

/**
 * NullAiClient — 启动时 AI 不可用的等价物（MIGRATION_PLAN §4.3，对应旧 NullAiService）。
 * 游戏照常启动，所有对话走规则兜底。
 */
public final class NullAiClient implements AiClient {

    private final RuleFallbackService fallback;

    public NullAiClient(Random rng) {
        this.fallback = new RuleFallbackService(rng);
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public ChatReply chatSync(ChatRequest request) {
        return ChatReply.fallback(fallback.reply(request));
    }
}

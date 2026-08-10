package com.canglan.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话请求（对应 Python 服务 POST /api/ai/chat 的载荷）。
 * memory 为召回的二层记忆片段（个体+群体），由调用方可选提供。
 */
public record ChatRequest(
        String npcId,
        String npcName,
        String playerName,
        String utterance,
        List<String> tags,
        List<String> memory) {

    public ChatRequest {
        tags = tags == null ? List.of() : new ArrayList<>(tags);
        memory = memory == null ? List.of() : new ArrayList<>(memory);
    }
}

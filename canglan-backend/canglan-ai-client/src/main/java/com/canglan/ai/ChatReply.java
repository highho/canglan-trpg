package com.canglan.ai;

/**
 * AI 对话回复。fallback=true 表示该回复来自规则兜底而非模型（便于前端区分展示）。
 */
public record ChatReply(String text, boolean fallback) {

    public static ChatReply of(String text) {
        return new ChatReply(text, false);
    }

    public static ChatReply fallback(String text) {
        return new ChatReply(text, true);
    }
}

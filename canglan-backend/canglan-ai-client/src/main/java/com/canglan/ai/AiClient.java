package com.canglan.ai;

/**
 * AiClient — AI 能力统一入口（MIGRATION_PLAN §4.3 降级铁律）。
 * 实现：LangGraphHttpClient（HTTP 真服务）/ NullAiClient（启动即不可用）。
 * 铁律：任何 AI 调用失败不得抛异常到游戏主流程；chatSync 失败一律返回规则兜底回复。
 */
public interface AiClient {

    /** 当前是否可用（探活 + 熔断状态综合判断，不抛异常）。 */
    boolean isAvailable();

    /**
     * 同步对话：内部含超时与熔断；失败/不可用时返回 {@link ChatReply#fallback(String)}，永不抛异常。
     */
    ChatReply chatSync(ChatRequest request);
}

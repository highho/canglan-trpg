package com.canglan.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * EmbeddedAiClient — ai-service/main.py LangGraph 管线的 Java 内嵌移植。
 * 管线：召回记忆 → 构建 prompt → LLM 生成（可选，失败降级）→ 安全过滤 → 写入记忆。
 * 零依赖、Android 兼容：APK 单机形态无需 Python 服务即可享有二层记忆对话；
 * 配置 -Dcanglan.ai.llm.url（OpenAI 兼容端点）后启用真模型生成。
 * 铁律：永不抛异常；无 LLM 或 LLM 失败时走带记忆召回的规则回复。
 */
public final class EmbeddedAiClient implements AiClient {

    /** 群体记忆传播域（与 Python 版一致）。 */
    private static final String[] GROUP_SCOPES = { "group:village", "group:guild" };
    private static final int REPLY_LIMIT = 200;

    private final NpcMemoryStore memory;
    private final OpenAiCompatClient llm;   // 可为 null（纯规则模式）
    private final AiCircuitBreaker breaker;
    private final RuleFallbackService fallback;

    public EmbeddedAiClient(Random rng, NpcMemoryStore memory, OpenAiCompatClient llm) {
        this.memory = memory;
        this.llm = llm;
        this.breaker = new AiCircuitBreaker(3, 30_000);
        this.fallback = new RuleFallbackService(rng);
    }

    @Override
    public boolean isAvailable() {
        return true;   // 本地管线恒可用（规则引擎兜底）
    }

    @Override
    public ChatReply chatSync(ChatRequest request) {
        try {
            ChatRequest req = request != null ? request
                    : new ChatRequest("", "村民", "旅人", "", null, null);
            List<String> mem = recallMemory(req);          // 召回记忆
            String prompt = buildPrompt(req, mem);         // 构建 prompt
            String text = null;
            boolean fromLlm = false;
            if (llm != null && breaker.allowRequest()) {   // LLM 生成（含熔断）
                text = llm.complete(prompt);
                if (text != null) {
                    breaker.recordSuccess();
                    fromLlm = true;
                } else {
                    breaker.recordFailure();
                }
            }
            if (!fromLlm) text = ruleReply(req, mem);      // 规则引擎兜底
            text = safetyFilter(text);                     // 安全过滤
            writeMemory(req);                              // 写入个体记忆
            return fromLlm ? ChatReply.of(text) : ChatReply.fallback(text);
        } catch (Exception ex) {
            return ChatReply.fallback(fallback.reply(request));   // 双保险：绝不抛异常
        }
    }

    // ==================== 管线节点（对应 Python 同名函数） ====================

    /** 召回二层记忆：个体（npcId）+ 群体（village/guild），各取最近 3 条，合计至多 6 条。 */
    private List<String> recallMemory(ChatRequest req) {
        List<String> mem = new ArrayList<>();
        if (req.npcId() != null && !req.npcId().isBlank()) mem.addAll(memory.recall(req.npcId(), 3));
        for (String scope : GROUP_SCOPES) mem.addAll(memory.recall(scope, 3));
        return mem.size() > 6 ? new ArrayList<>(mem.subList(0, 6)) : mem;
    }

    private static String buildPrompt(ChatRequest req, List<String> mem) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 TRPG 世界中的 NPC「").append(orDefault(req.npcName(), "村民"))
                .append("」。请依据设定与记忆，用不超过两句话、符合世界观的口吻回应玩家。\n");
        sb.append("玩家标签：").append(req.tags().isEmpty() ? "无" : String.join(", ", req.tags())).append('\n');
        sb.append("你的记忆：\n");
        if (mem.isEmpty()) sb.append("- （暂无记忆）\n");
        else for (String m : mem) sb.append("- ").append(m).append('\n');
        sb.append("玩家「").append(orDefault(req.playerName(), "旅人")).append("」说：")
                .append(orDefault(req.utterance(), ""));
        return sb.toString();
    }

    /** 规则回复（带记忆召回）：关键词命中 → 固定文案；否则有记忆 → 回忆模板；否则通用文案。 */
    private String ruleReply(ChatRequest req, List<String> mem) {
        String keyword = fallback.matchKeyword(req.utterance());
        if (keyword != null) return keyword;
        if (!mem.isEmpty()) {
            return "（" + orDefault(req.npcName(), "村民") + "回忆道）说起来……" + mem.get(0)
                    + "至于你问的事，我也不太清楚。";
        }
        return fallback.generic();
    }

    /** 安全过滤：剥离控制字符、截断过长内容。 */
    private static String safetyFilter(String text) {
        String cleaned = text == null ? "" : text.replaceAll("[\\x00-\\x08\\x0b-\\x1f]", "");
        if (cleaned.length() > REPLY_LIMIT) cleaned = cleaned.substring(0, REPLY_LIMIT);
        cleaned = cleaned.trim();
        return cleaned.isEmpty() ? "……（对方沉默不语）" : cleaned;
    }

    /** 对话后写入个体记忆（重要性按话语长度粗估 1~2，与 Python 版一致）。 */
    private void writeMemory(ChatRequest req) {
        String utterance = req.utterance() == null ? "" : req.utterance().trim();
        if (req.npcId() == null || req.npcId().isBlank() || utterance.isEmpty()) return;
        int importance = utterance.length() > 20 ? 2 : 1;
        String content = utterance.length() > 80 ? utterance.substring(0, 80) : utterance;
        memory.append(req.npcId(), "玩家说：" + content, importance);
    }

    private static String orDefault(String s, String dft) {
        return s == null || s.isBlank() ? dft : s;
    }
}

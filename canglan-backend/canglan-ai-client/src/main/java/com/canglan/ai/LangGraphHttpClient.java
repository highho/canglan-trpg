package com.canglan.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.json.JsonWriter;

/**
 * LangGraphHttpClient — AiClient 的 HTTP 实现（MIGRATION_PLAN §4.2/§4.3）。
 * 超时 3s；连续 3 次失败熔断 30s；任何失败都转为规则兜底回复，绝不抛异常。
 */
public final class LangGraphHttpClient implements AiClient {

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final String baseUrl;
    private final HttpClient http;
    private final AiCircuitBreaker breaker;
    private final RuleFallbackService fallback;

    public LangGraphHttpClient(String baseUrl, Random rng) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.breaker = new AiCircuitBreaker(3, 30_000);
        this.fallback = new RuleFallbackService(rng);
    }

    /** 探活 GET /health；供启动工厂使用。 */
    public boolean probe() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                breaker.recordSuccess();
                return true;
            }
        } catch (Exception ignored) { }
        breaker.recordFailure();
        return false;
    }

    @Override
    public boolean isAvailable() {
        return !breaker.isOpen();
    }

    @Override
    public ChatReply chatSync(ChatRequest request) {
        if (!breaker.allowRequest()) {
            return ChatReply.fallback(fallback.reply(request));
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/ai/chat"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(request), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
            JsonValue v = JsonReader.parse(resp.body());
            String reply = v.getString("reply", "");
            if (reply.isEmpty()) throw new RuntimeException("空回复");
            breaker.recordSuccess();
            return ChatReply.of(reply);
        } catch (Exception ex) {
            breaker.recordFailure();
            return ChatReply.fallback(fallback.reply(request));
        }
    }

    private static String toJson(ChatRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("npcId", r.npcId());
        m.put("npcName", r.npcName());
        m.put("playerName", r.playerName());
        m.put("utterance", r.utterance());
        m.put("tags", r.tags());
        m.put("memory", r.memory());
        return JsonWriter.write(m, -1);
    }
}

package com.canglan.ai;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.json.JsonWriter;

/**
 * OpenAiCompatClient — OpenAI 兼容 /v1/chat/completions 客户端（ai-service/main.py node_generate 的 Java 移植）。
 * 使用 HttpURLConnection 而非 java.net.http.HttpClient（Android API 30 兼容）。
 * 任何失败（超时/网络/非200/解析错误）返回 null，由调用方降级规则引擎，永不抛异常。
 */
public final class OpenAiCompatClient {

    private final String baseUrl;
    private final String model;
    private final int timeoutMillis;

    public OpenAiCompatClient(String baseUrl, String model, int timeoutMillis) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model == null || model.isBlank() ? "local-model" : model;
        this.timeoutMillis = timeoutMillis;
    }

    /** 生成一次补全；失败返回 null。 */
    public String complete(String prompt) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/v1/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            body.put("max_tokens", 128);
            body.put("temperature", 0.7);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(JsonWriter.write(body, -1).getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) return null;

            String text;
            try (InputStream is = conn.getInputStream()) {
                text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonValue v = JsonReader.parse(text);
            JsonValue choices = v.get("choices");
            if (choices == null || !choices.isArray() || choices.asArray().isEmpty()) return null;
            JsonValue message = choices.asArray().get(0).get("message");
            if (message == null) return null;
            String content = message.getString("content", "").trim();
            return content.isEmpty() ? null : content;
        } catch (Exception ex) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

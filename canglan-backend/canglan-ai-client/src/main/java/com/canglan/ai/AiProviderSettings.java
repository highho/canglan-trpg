package com.canglan.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.json.JsonWriter;

/**
 * AiProviderSettings — AI 供应商接入配置（全局单例，运行时可改、立即生效）。
 * 供应商统一为 OpenAI 兼容端点：本地模型服务（Ollama/llama.cpp）与云端模型（OpenAI/DeepSeek 等）同构，
 * 差异仅在 baseUrl/apiKey/model。配置持久化于 saveDir/ai-config.json，Android 沙箱同样适用。
 * 兼容：无配置文件时以 -Dcanglan.ai.llm.url / -Dcanglan.ai.llm.model 作为初始值。
 */
public final class AiProviderSettings {

    /** 供应商配置（enabled=是否启用 LLM 生成；apiKey 本地服务可留空）。 */
    public record Config(boolean enabled, String baseUrl, String apiKey, String model) {
        public static Config disabled() {
            return new Config(false, "", "", "");
        }
    }

    private static volatile Config config = Config.disabled();
    private static volatile OpenAiCompatClient llmClient;
    private static volatile Path configFile;

    private AiProviderSettings() {}

    /** 启动时调用：从文件加载（不存在则用系统属性播种），并记住文件位置供保存。 */
    public static synchronized void init(Path file) {
        configFile = file;
        Config loaded = null;
        try {
            if (file != null && Files.exists(file)) {
                JsonValue root = JsonReader.parse(Files.readString(file, StandardCharsets.UTF_8));
                loaded = new Config(
                        root.getBoolean("enabled", false),
                        root.getString("baseUrl", ""),
                        root.getString("apiKey", ""),
                        root.getString("model", ""));
            }
        } catch (Exception ignored) {
            // 配置损坏 → 回退禁用，不影响启动
        }
        if (loaded == null) {
            String url = System.getProperty("canglan.ai.llm.url", System.getenv("LLM_BASE_URL"));
            loaded = (url == null || url.isBlank())
                    ? Config.disabled()
                    : new Config(true, url, "", System.getProperty("canglan.ai.llm.model", "local-model"));
        }
        apply(loaded);
    }

    /** 更新配置（前端保存入口）：立即生效 + 落盘。 */
    public static synchronized void update(Config next) {
        apply(next);
        save();
    }

    public static Config get() {
        return config;
    }

    /** 当前生效的 LLM 客户端；未启用/未配置返回 null（调用方走规则兜底）。 */
    public static OpenAiCompatClient client() {
        return llmClient;
    }

    /** 供 /api/health 展示：是否已配置并启用供应商。 */
    public static boolean llmEnabled() {
        return llmClient != null;
    }

    private static void apply(Config next) {
        config = next == null ? Config.disabled() : next;
        llmClient = (config.enabled() && !config.baseUrl().isBlank())
                ? new OpenAiCompatClient(config.baseUrl(), config.apiKey(),
                        config.model().isBlank() ? "local-model" : config.model(), 8_000)
                : null;
    }

    private static void save() {
        try {
            if (configFile == null) return;
            if (configFile.getParent() != null) Files.createDirectories(configFile.getParent());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", config.enabled());
            m.put("baseUrl", config.baseUrl());
            m.put("apiKey", config.apiKey());
            m.put("model", config.model());
            Files.writeString(configFile, JsonWriter.write(m, 1), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 落盘失败仅内存生效（不得影响游戏）
        }
    }

    /** 测试用：重置为禁用且清除文件位置。 */
    static synchronized void resetForTest() {
        configFile = null;
        apply(Config.disabled());
    }
}

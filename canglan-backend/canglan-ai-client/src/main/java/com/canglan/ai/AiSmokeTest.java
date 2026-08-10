package com.canglan.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * AiSmokeTest — P7 验收 + AI 内嵌化验收：
 * 禁用/降级路径、内嵌管线（二层记忆+规则）、LLM 端点（stub）、在线外部服务（stub）四路径。
 * 内嵌 JDK HttpServer 充当 stub，零外部依赖、零 Python 依赖。
 * 用法：java com.canglan.ai.AiSmokeTest <dataDir>
 */
public final class AiSmokeTest {

    private static int pass, fail;

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "../data");

        offlinePaths();
        embeddedPaths();
        llmPath();
        onlinePath(dataDir);

        System.out.println();
        System.out.println("通过: " + pass + "  失败: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ==================== 禁用/降级路径 ====================

    private static void offlinePaths() {
        System.out.println("== 禁用/降级路径 ==");

        // "off" 显式禁用 → NullAiClient
        AiClient disabled = AiClients.connect("off", new Random(1), null);
        check("off 显式禁用 NullAiClient", disabled instanceof NullAiClient && !disabled.isAvailable());

        // 空 URL → 内嵌管线（默认形态，恒可用）
        AiClient embedded = AiClients.connect("", new Random(1), tempDir());
        check("空 URL 构建内嵌管线", embedded instanceof EmbeddedAiClient && embedded.isAvailable());

        // 外部服务探活失败 → 降级内嵌管线（端口 1 必然拒绝连接）
        AiClient down = AiClients.connect("http://127.0.0.1:1", new Random(1), tempDir());
        check("探活失败降级内嵌管线", down instanceof EmbeddedAiClient && down.isAvailable());

        // NullAiClient 的规则兜底：关键词命中 + 永不抛异常
        ChatReply kw = disabled.chatSync(new ChatRequest("npc", "铁匠", "旅人",
                "最近有什么任务吗", null, null));
        check("规则兜底关键词命中", kw.fallback() && kw.text().contains("公会"));
        ChatReply generic = disabled.chatSync(new ChatRequest("npc", "铁匠", "旅人", "啦啦啦", null, null));
        check("规则兜底通用文案", generic.fallback() && !generic.text().isEmpty());
    }

    // ==================== 内嵌管线（二层记忆 + 规则） ====================

    private static void embeddedPaths() {
        System.out.println("== 内嵌管线（记忆+规则） ==");
        Path dir = tempDir();
        AiClient ai = AiClients.connect("", new Random(7), dir);

        // 第一回合：关键词命中 + 写入个体记忆
        ChatReply first = ai.chatSync(new ChatRequest("npc_smith", "铁匠汉斯", "测试者", "你好啊", null, null));
        check("内嵌关键词命中", first.fallback() && first.text().contains("是你啊"));
        check("个体记忆已落盘", Files.exists(dir.resolve("memories.json")));

        // 第二回合：无关键词命中 → 召回上一回合记忆（回忆模板）
        ChatReply second = ai.chatSync(new ChatRequest("npc_smith", "铁匠汉斯", "测试者", "啦啦啦", null, null));
        check("无命中召回记忆", second.fallback() && second.text().contains("回忆")
                && second.text().contains("你好啊"));

        // 记忆跨实例持久化：同目录重建客户端仍能召回
        AiClient reloaded = AiClients.connect("", new Random(7), dir);
        ChatReply third = reloaded.chatSync(new ChatRequest("npc_smith", "铁匠汉斯", "测试者", "随便聊聊", null, null));
        check("记忆跨实例持久化", third.text().contains("玩家说"));
    }

    // ==================== 内嵌管线 + LLM 端点（stub） ====================

    private static void llmPath() throws Exception {
        System.out.println("== 内嵌管线 + LLM 端点（stub） ==");
        AtomicInteger mode = new AtomicInteger(0);   // 0=正常 1=500
        HttpServer stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            if (mode.get() == 1) {
                respond(ex, 500, "{\"error\":\"boom\"}");
            } else {
                respond(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"LLM 生成的问候\"}}]}");
            }
        });
        stub.setExecutor(daemonPool());
        stub.start();
        String stubUrl = "http://127.0.0.1:" + stub.getAddress().getPort();
        try {
            // 供应商配置运行时生效：update 后新建会话即走 LLM
            AiProviderSettings.update(new AiProviderSettings.Config(true, stubUrl, "", "stub-model"));
            check("供应商配置启用后 client 非空", AiProviderSettings.llmEnabled());
            AiClient ai = AiClients.connect("", new Random(9), tempDir());
            ChatReply ok = ai.chatSync(new ChatRequest("npc", "村长", "旅人", "你好", null, null));
            check("LLM 生成成功", !ok.fallback() && ok.text().contains("LLM 生成的问候"));

            // 供应商运行时切换：已有会话立即改用新供应商（此处切到 500 stub → 降级规则）
            mode.set(1);
            ChatReply bad = ai.chatSync(new ChatRequest("npc", "村长", "旅人", "你好", null, null));
            check("LLM 失败降级规则", bad.fallback() && !bad.text().isEmpty());

            // 配置持久化：落盘 → 重新 init → 仍启用
            java.nio.file.Path cfgFile = tempDir().resolve("ai-config.json");
            AiProviderSettings.resetForTest();
            AiProviderSettings.init(cfgFile);
            check("配置未落盘时 init 回退禁用", !AiProviderSettings.llmEnabled());
            AiProviderSettings.update(new AiProviderSettings.Config(true, stubUrl, "sk-x", "stub-model"));
            AiProviderSettings.resetForTest();
            AiProviderSettings.init(cfgFile);
            check("配置跨重启持久化", AiProviderSettings.llmEnabled()
                    && "sk-x".equals(AiProviderSettings.get().apiKey()));
        } finally {
            AiProviderSettings.resetForTest();
            stub.stop(0);
        }
    }

    // ==================== 在线外部服务路径（stub） ====================

    private static void onlinePath(Path dataDir) throws Exception {
        System.out.println("== 在线路径（外部服务 stub） ==");
        AtomicInteger mode = new AtomicInteger(0);   // 0=正常 1=500
        HttpServer stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/health", ex -> respond(ex, 200, "{\"status\":\"ok\"}"));
        stub.createContext("/api/ai/chat", ex -> {
            ex.getRequestBody().readAllBytes();
            if (mode.get() == 1) {
                respond(ex, 500, "{\"error\":\"boom\"}");
            } else {
                respond(ex, 200, "{\"reply\":\"stub 自由对话回复\"}");
            }
        });
        stub.setExecutor(daemonPool());
        stub.start();
        String url = "http://127.0.0.1:" + stub.getAddress().getPort();
        try {
            // 探活成功 → LangGraphHttpClient
            AiClient online = AiClients.connect(url, new Random(2), tempDir());
            check("探活成功建 LangGraphHttpClient", online instanceof LangGraphHttpClient && online.isAvailable());
            ChatReply ok = online.chatSync(new ChatRequest("npc_smith", "铁匠汉斯", "测试者",
                    "今天天气如何", java.util.List.of(), java.util.List.of()));
            check("在线对话返回 stub 回复", !ok.fallback() && ok.text().contains("stub"));

            // 连续失败 → 熔断开启 → isAvailable=false 且回复走规则兜底
            mode.set(1);
            LangGraphHttpClient http = (LangGraphHttpClient) online;
            for (int i = 0; i < 3; i++) {
                ChatReply bad = http.chatSync(new ChatRequest("npc", "铁匠", "旅人", "你好", null, null));
                check("失败第 " + (i + 1) + " 次转兜底", bad.fallback());
            }
            check("三次失败后熔断开启", !http.isAvailable());
            ChatReply during = http.chatSync(new ChatRequest("npc", "铁匠", "旅人", "你好", null, null));
            check("熔断期直接规则兜底（无网络调用）", during.fallback());

            // GameSession 接线：未识别指令走 AI 自由对话（stub 正常时）
            mode.set(0);
            System.setProperty("canglan.ai.url", url);
            var session = new com.canglan.api.GameSession(dataDir, Path.of("build-ai-test"),
                    new Random(3), com.canglan.world.DifficultyMode.NORMAL);
            session.execute("创建 测试者");
            session.execute("1");
            session.execute("1");
            session.execute("1");
            String text = session.execute("今天心情不错啊").text();
            check("GameSession 自由对话接入 AI", text.contains("stub 自由对话回复"));
        } finally {
            stub.stop(0);
            System.clearProperty("canglan.ai.url");
        }
    }

    // ==================== 工具 ====================

    private static java.util.concurrent.ExecutorService daemonPool() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ai-stub");
            t.setDaemon(true);
            return t;
        });
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("ai-embed");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }
}

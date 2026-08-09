package com.canglan.ai;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * AiSmokeTest — P7 验收：AI 不可用时规则回退正常（在线/失败熔断/离线三路径 + GameSession 接线）。
 * 内嵌 JDK HttpServer 充当 AI stub，零外部依赖、零 Python 依赖。
 * 用法：java com.canglan.ai.AiSmokeTest <dataDir>
 */
public final class AiSmokeTest {

    private static int pass, fail;

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "../data");

        offlinePaths();
        onlinePath(dataDir);

        System.out.println();
        System.out.println("通过: " + pass + "  失败: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ==================== 离线/禁用路径 ====================

    private static void offlinePaths() {
        System.out.println("== 离线/禁用路径 ==");

        // 显式禁用 → NullAiClient
        AiClient disabled = AiClients.connect("", new Random(1));
        check("空 URL 降级 NullAiClient", disabled instanceof NullAiClient && !disabled.isAvailable());

        // 探活失败 → NullAiClient（端口 1 必然拒绝连接）
        AiClient down = AiClients.connect("http://127.0.0.1:1", new Random(1));
        check("探活失败降级 NullAiClient", down instanceof NullAiClient);

        // NullAiClient 的规则兜底：关键词命中 + 永不抛异常
        ChatReply kw = disabled.chatSync(new ChatRequest("npc", "铁匠", "旅人",
                "最近有什么任务吗", null, null));
        check("规则兜底关键词命中", kw.fallback() && kw.text().contains("公会"));
        ChatReply generic = disabled.chatSync(new ChatRequest("npc", "铁匠", "旅人", "啦啦啦", null, null));
        check("规则兜底通用文案", generic.fallback() && !generic.text().isEmpty());
    }

    // ==================== 在线路径（stub 服务） ====================

    private static void onlinePath(Path dataDir) throws Exception {
        System.out.println("== 在线路径（stub 服务） ==");
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
        stub.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ai-stub");
            t.setDaemon(true);
            return t;
        }));
        stub.start();
        String url = "http://127.0.0.1:" + stub.getAddress().getPort();
        try {
            // 探活成功 → LangGraphHttpClient
            AiClient online = AiClients.connect(url, new Random(2));
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

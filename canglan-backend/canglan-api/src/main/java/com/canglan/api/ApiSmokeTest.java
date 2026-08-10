package com.canglan.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.world.DifficultyMode;

/**
 * ApiSmokeTest — P6 验收：32 指令全跑 + HTTP REST 往返。
 * 用法：java com.canglan.api.ApiSmokeTest <dataDir> [saveDir=build-api-test]
 */
public final class ApiSmokeTest {

    private static int pass, fail;

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "../data");
        Path saveDir = Path.of(args.length > 1 ? args[1] : "build-api-test");
        if (Files.exists(saveDir)) {
            try (var walk = Files.walk(saveDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(saveDir);

        sessionCommands(dataDir, saveDir);
        httpRoundTrip(dataDir, saveDir);

        System.out.println();
        System.out.println("通过: " + pass + "  失败: " + fail);
        if (fail > 0) System.exit(1);
    }

    // ==================== Part A：GameSession 直驱 32 指令 ====================

    private static void sessionCommands(Path dataDir, Path saveDir) {
        System.out.println("== Part A: GameSession 32 指令 ==");
        GameSession s = new GameSession(dataDir, saveDir, new Random(42), DifficultyMode.NORMAL);

        // 未建档时的守门
        check("未建档守门", s.execute("状态").text().contains("还没有角色"));

        // 建档三选
        check("创建进入选种族", s.execute("创建 测试者").text().contains("1/3"));
        check("取消建档", s.execute("取消").text().contains("取消建档"));
        s.execute("创建 测试者");
        check("选种族", s.execute("1").text().contains("2/3"));
        check("选职业", s.execute("1").text().contains("3/3"));
        CommandResult created = s.execute("1");
        check("建档完成", Boolean.TRUE.equals(created.hud().get("hasPlayer")));
        check("建档后回到指令模式", s.creationStage == 0);

        // 依次跑完 32 指令：只要无异常 + 有叙事行即通过
        String[] commands = {
                "帮助", "状态", "背包", "标签", "装备",
                "东", "南", "西", "北", "前往 东",
                "查看", "探索", "等待", "交谈", "攻击 哥布林",
                "采集", "吃 面包", "喝水", "制造", "配方",
                "任务", "传闻", "完成 新手之路", "技能", "解锁技能",
                "建造 木屋", "家园", "家园升级", "招募", "交易",
                "购买", "出售", "存档", "存档列表", "读档", "声望",
        };
        int stepBefore = s.stepCount;
        for (String cmd : commands) {
            CommandResult r = s.execute(cmd);
            check("指令「" + cmd + "」", r != null && r.lines().size() >= 2
                    && r.lines().stream().noneMatch(l -> l.kind() == NarrationKind.ERROR));
        }
        check("移动累计步数", s.stepCount > stepBefore);
        check("存档落盘", !s.saveManager.listSlots().isEmpty());

        // 未识别指令 → AI 自由对话回退（P6 固定提示）
        check("自由对话回退", s.execute("今天天气如何").text().contains("帮助"));
        // 全角空格归一化：「命令　参数」应与半角空格等价（用 char 拼接避免 javac 词法级 unicode 转义）
        int stepFw = s.stepCount;
        s.execute("前往" + (char) 0x3000 + "东");
        check("全角空格归一化", s.stepCount == stepFw + 1);
    }

    // ==================== Part B：HTTP REST 往返 ====================

    private static void httpRoundTrip(Path dataDir, Path saveDir) throws Exception {
        System.out.println("== Part B: HTTP REST 往返 ==");
        HttpApiServer api = new HttpApiServer(dataDir, saveDir);
        api.start(0);
        int port = api.port();
        String base = "http://localhost:" + port;
        HttpClient client = HttpClient.newHttpClient();
        try {
            // health
            HttpResponse<String> health = send(client, "GET", base + "/api/health", null);
            check("GET /api/health 200", health.statusCode() == 200
                    && health.body().contains("\"status\": \"ok\"")
                    || health.body().contains("\"status\":\"ok\""));

            // new game
            HttpResponse<String> created = send(client, "POST", base + "/api/game/new", "{\"difficulty\":\"NORMAL\"}");
            check("POST /api/game/new 200", created.statusCode() == 200);
            String sessionId = JsonReader.parse(created.body()).getString("sessionId", "");
            check("sessionId 非空", !sessionId.isEmpty());

            // command：建档三选
            String cmdUrl = base + "/api/game/command";
            check("command 创建", command(client, cmdUrl, sessionId, "创建 网络旅人").contains("1/3"));
            check("command 选种族", command(client, cmdUrl, sessionId, "1").contains("2/3"));
            check("command 选职业", command(client, cmdUrl, sessionId, "1").contains("3/3"));
            String finish = command(client, cmdUrl, sessionId, "1");
            check("command 建档完成", finish.contains("测试")|| finish.contains("冒险")|| finish.contains("命运"));

            // 存档 / 槽位 / 读档
            HttpResponse<String> save = send(client, "POST", base + "/api/save/2?sessionId=" + sessionId, "");
            check("POST /api/save/2 200", save.statusCode() == 200);
            HttpResponse<String> slots = send(client, "GET", base + "/api/save/slots?sessionId=" + sessionId, null);
            check("GET /api/save/slots 含槽位", slots.statusCode() == 200 && slots.body().contains("\"slot\""));
            HttpResponse<String> load = send(client, "POST", base + "/api/load/2?sessionId=" + sessionId, "");
            check("POST /api/load/2 200", load.statusCode() == 200);

            // 错误路径：未知会话 404 / 非法槽位 400
            HttpResponse<String> badSession = send(client, "POST", base + "/api/game/command",
                    "{\"sessionId\":\"nope\",\"line\":\"状态\"}");
            check("未知会话 404", badSession.statusCode() == 404);
            HttpResponse<String> badSlot = send(client, "POST",
                    base + "/api/save/99?sessionId=" + sessionId, "");
            check("非法槽位 400", badSlot.statusCode() == 400);

            // AI 供应商配置：默认禁用 → 保存 → 回读一致 → health.llm 联动 → 试连失败路径
            HttpResponse<String> cfg0 = send(client, "GET", base + "/api/ai/config", null);
            check("GET /api/ai/config 200", cfg0.statusCode() == 200 && cfg0.body().contains("enabled"));
            HttpResponse<String> cfgSave = send(client, "POST", base + "/api/ai/config",
                    "{\"enabled\":true,\"baseUrl\":\"http://127.0.0.1:1/v1\",\"apiKey\":\"sk-test\",\"model\":\"qwen2\"}");
            check("POST /api/ai/config 200", cfgSave.statusCode() == 200);
            HttpResponse<String> cfg1 = send(client, "GET", base + "/api/ai/config", null);
            check("配置保存后回读一致", cfg1.body().contains("sk-test") && cfg1.body().contains("qwen2"));
            HttpResponse<String> health2 = send(client, "GET", base + "/api/health", null);
            check("health.llm 随配置联动", health2.body().contains("\"llm\": true")
                    || health2.body().contains("\"llm\":true"));
            HttpResponse<String> test = send(client, "POST", base + "/api/ai/test",
                    "{\"baseUrl\":\"http://127.0.0.1:1/v1\",\"apiKey\":\"\",\"model\":\"x\"}");
            check("试连失败返回 ok=false", test.statusCode() == 200 && test.body().contains("\"ok\": false")
                    || test.body().contains("\"ok\":false"));
            send(client, "POST", base + "/api/ai/config", "{\"enabled\":false}");   // 复原禁用
        } finally {
            api.stop();
        }
    }

    private static String command(HttpClient client, String url, String sessionId, String line)
            throws Exception {
        String body = "{\"sessionId\":" + quote(sessionId) + ",\"line\":" + quote(line) + "}";
        HttpResponse<String> resp = send(client, "POST", url, body);
        if (resp.statusCode() != 200) return "[HTTP " + resp.statusCode() + "]";
        JsonValue v = JsonReader.parse(resp.body());
        StringBuilder sb = new StringBuilder();
        for (JsonValue n : v.get("narration").asArray())
            sb.append(n.getString("text", "")).append('\n');
        return sb.toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static HttpResponse<String> send(HttpClient client, String method, String url, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8");
        if (method.equals("GET")) b.GET();
        else b.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

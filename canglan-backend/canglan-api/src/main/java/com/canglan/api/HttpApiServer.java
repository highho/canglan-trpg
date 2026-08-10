package com.canglan.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.canglan.ai.AiProviderSettings;
import com.canglan.ai.OpenAiCompatClient;
import com.canglan.core.graph.ClassNode;
import com.canglan.core.graph.QuestNode;
import com.canglan.core.graph.RaceNode;
import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.json.JsonWriter;
import com.canglan.data.craft.Recipe;
import com.canglan.data.item.ItemDef;
import com.canglan.data.item.ItemStack;
import com.canglan.data.monster.MonsterTemplate;
import com.canglan.data.skill.Skill;
import com.canglan.data.trait.TraitDef;
import com.canglan.save.SaveSlotInfo;
import com.canglan.world.BiomeType;
import com.canglan.world.DifficultyMode;
import com.canglan.world.MapPos;
import com.canglan.world.TerrainFeature;
import com.canglan.data.equipment.EquipSlot;
import com.canglan.world.equipment.Equip;

/**
 * HttpApiServer — REST 层（MIGRATION_PLAN §7a）。
 * 传输层为自研 MiniHttpServer（ServerSocket 实现），PC 与 Android 共用——
 * JDK 内置 com.sun.net.httpserver 在 Android 上不存在。
 * 端点全集：
 *   GET  /api/health           探活（含 AI 可用性）
 *   POST /api/game/new         新游戏 → {sessionId}
 *   POST /api/game/command     {sessionId, line} → {narration[], hud}
 *   POST /api/save/{slot}      手动存档到指定槽位
 *   POST /api/load/{slot}      读档
 *   GET  /api/save/slots       槽位列表（?sessionId=xxx）
 *   GET  /api/game/state       全量叙事日志 + HUD（前端刷新恢复）
 *   GET  /api/panel/{name}     覆盖层面板数据（char/bag/skill/quest/home/recipe/settings/map/codex）
 *   GET  /api/creation/options 创建页选项（血脉/道路/特质，随选择过滤）
 *   POST /api/game/start       一步建档（name/race/clazz/trait/difficulty）→ 会话 + 完整叙事
 *   GET  /api/ai/config        AI 供应商配置（启用/地址/密钥/模型）
 *   POST /api/ai/config        保存供应商配置（立即生效 + 持久化）
 *   POST /api/ai/test          按载荷配置试连供应商（不落盘）
 *   非 /api 路径               静态托管 frontend/dist（P8 前端产物）
 * CORS 头与 OPTIONS 预检由 MiniHttpServer 统一处理。
 */
public final class HttpApiServer {

    private final Path dataDir;
    private final Path saveDir;
    private final Path staticDir;
    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private MiniHttpServer server;

    public HttpApiServer(Path dataDir, Path saveDir) {
        this(dataDir, saveDir, Path.of("frontend", "dist"));
    }

    public HttpApiServer(Path dataDir, Path saveDir, Path staticDir) {
        this.dataDir = dataDir;
        this.saveDir = saveDir;
        this.staticDir = staticDir;
        AiProviderSettings.init(saveDir.resolve("ai-config.json"));   // 供应商配置：文件 > 系统属性播种
    }

    /** 会话注册表只读视图（冒烟测试用）。 */
    public Map<String, GameSession> sessions() {
        return sessions;
    }

    public void start(int port) throws IOException {
        server = new MiniHttpServer();
        server.route("/api/health", this::handleHealth);
        server.route("/api/game/new", this::handleNew);
        server.route("/api/game/command", this::handleCommand);
        server.route("/api/save/slots", this::handleSlots);
        server.route("/api/save/", this::handleSaveSlot);
        server.route("/api/load/", this::handleLoadSlot);
        server.route("/api/game/state", this::handleState);
        server.route("/api/game/start", this::handleStart);
        server.route("/api/creation/options", this::handleCreationOptions);
        server.route("/api/ai/config", this::handleAiConfig);
        server.route("/api/ai/test", this::handleAiTest);
        server.route("/api/panel/", this::handlePanel);
        server.route("/", this::handleStatic);
        server.start(port);
    }

    public void stop() {
        if (server != null) server.stop();
    }

    /** 实际监听端口（传 0 启动时由系统分配，冒烟测试用）。 */
    public int port() {
        return server == null ? -1 : server.port();
    }

    // ==================== 端点实现 ====================

    private void handleHealth(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "GET")) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        // AI 可用性：取任一会话的 AiClient 状态（无会话时视为不可用）
        boolean aiUp = sessions.values().stream().anyMatch(gs -> gs.ai.isAvailable());
        body.put("aiAvailable", aiUp);
        body.put("llm", AiProviderSettings.llmEnabled());   // 供应商是否已配置并启用
        body.put("sessions", sessions.size());
        sendJson(resp, 200, body);
    }

    /** GET/POST /api/ai/config：AI 供应商配置（本地模型服务与云端模型同构：地址/密钥/模型）。 */
    private void handleAiConfig(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if ("POST".equals(req.method)) {
            JsonValue body = readJson(req);
            if (body == null) {
                sendError(resp, 400, "请求体需为 JSON 对象 {enabled, baseUrl, apiKey, model}");
                return;
            }
            AiProviderSettings.update(new AiProviderSettings.Config(
                    body.getBoolean("enabled", false),
                    body.getString("baseUrl", "").trim(),
                    body.getString("apiKey", "").trim(),
                    body.getString("model", "").trim()));
        } else if (!requireMethod(req, resp, "GET")) {
            return;
        }
        AiProviderSettings.Config c = AiProviderSettings.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", c.enabled());
        out.put("baseUrl", c.baseUrl());
        out.put("apiKey", c.apiKey());
        out.put("model", c.model());
        sendJson(resp, 200, out);
    }

    /** POST /api/ai/test：按载荷配置试连供应商（不落盘）；结果以 {ok, reply|error} 返回，永不 5xx。 */
    private void handleAiTest(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "POST")) return;
        JsonValue body = readJson(req);
        String baseUrl = body == null ? "" : body.getString("baseUrl", "").trim();
        if (baseUrl.isBlank()) {
            sendError(resp, 400, "服务地址不能为空");
            return;
        }
        String model = body.getString("model", "").trim();
        OpenAiCompatClient probe = new OpenAiCompatClient(baseUrl,
                body.getString("apiKey", "").trim(), model.isBlank() ? "local-model" : model, 8_000);
        String reply = probe.complete("你是文字冒险游戏里的村长，请用一句简短的中文问候玩家。", 48);
        Map<String, Object> out = new LinkedHashMap<>();
        if (reply != null) {
            out.put("ok", true);
            out.put("reply", reply);
        } else {
            out.put("ok", false);
            out.put("error", "无法连接供应商或生成失败：请核对服务地址、API 密钥与模型名（云端需含 /v1 前缀的 OpenAI 兼容地址）");
        }
        sendJson(resp, 200, out);
    }

    private void handleNew(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "POST")) return;
        JsonValue body = readJson(req);
        DifficultyMode mode = DifficultyMode.NORMAL;
        String raw = body == null ? "" : body.getString("difficulty", "");
        if (!raw.isEmpty()) {
            try {
                mode = DifficultyMode.valueOf(raw.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendError(resp, 400, "未知难度：" + raw);
                return;
            }
        }
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new GameSession(dataDir, saveDir, new Random(), mode));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("difficulty", mode.name());
        sendJson(resp, 200, out);
    }

    private void handleCommand(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "POST")) return;
        JsonValue body = readJson(req);
        if (body == null) { sendError(resp, 400, "请求体需为 JSON 对象 {sessionId, line}"); return; }
        GameSession session = sessions.get(body.getString("sessionId", ""));
        if (session == null) { sendError(resp, 404, "会话不存在，请先 POST /api/game/new"); return; }
        String line = body.getString("line", "");
        CommandResult result;
        synchronized (session) {
            result = session.execute(line);
        }
        sendJson(resp, 200, commandToJson(result));
    }

    private void handleSaveSlot(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "POST")) return;
        GameSession session = resolveSession(req, resp);
        if (session == null) return;
        int slot = parseSlot(resp, pathTail(req.path));
        if (slot < 0) return;
        CommandResult result;
        synchronized (session) {
            session.selectedSlot = slot;
            result = session.execute("存档");
        }
        sendJson(resp, 200, commandToJson(result));
    }

    private void handleLoadSlot(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "POST")) return;
        GameSession session = resolveSession(req, resp);
        if (session == null) return;
        int slot = parseSlot(resp, pathTail(req.path));
        if (slot < 0) return;
        CommandResult result;
        synchronized (session) {
            session.selectedSlot = slot;
            result = session.execute("读档");
        }
        sendJson(resp, 200, commandToJson(result));
    }

    private void handleSlots(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "GET")) return;
        // ?sessionId=xxx；缺省时仅允许只有一个会话
        String sessionId = queryParams(req.query).getOrDefault("sessionId", "");
        GameSession session = sessionId.isEmpty() && sessions.size() == 1
                ? sessions.values().iterator().next()
                : sessions.get(sessionId);
        if (session == null) { sendError(resp, 404, "会话不存在，请通过 ?sessionId= 指定"); return; }
        List<Object> slots = new ArrayList<>();
        for (SaveSlotInfo info : session.saveManager.listSlots()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", info.slot());
            m.put("timestamp", info.timestamp());
            m.put("playTime", info.playTime());
            m.put("location", info.location());
            m.put("level", info.level());
            slots.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slots", slots);
        sendJson(resp, 200, out);
    }

    // ==================== P8：状态恢复 / 面板 / 静态托管 ====================

    /** GET /api/game/state：全量叙事日志 + HUD（前端刷新恢复）。 */
    private void handleState(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "GET")) return;
        GameSession session = resolveSession(req, resp);
        if (session == null) return;
        Map<String, Object> out;
        synchronized (session) {
            List<Object> log = new ArrayList<>();
            for (NarrationLine line : session.log()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("text", line.text());
                m.put("kind", line.kind().name());
                log.add(m);
            }
            out = new LinkedHashMap<>();
            out.put("log", log);
            out.put("hud", session.hud());
        }
        sendJson(resp, 200, out);
    }

    /** GET /api/creation/options：创建页选项（血脉/道路/特质，随 race/clazz 过滤，对齐原 Avalonia 创建页）。 */
    private void handleCreationOptions(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "GET")) return;
        GameSession probe = new GameSession(dataDir, saveDir, new Random(), DifficultyMode.NORMAL);
        Map<String, String> q = queryParams(req.query);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> races = new ArrayList<>();
        for (RaceNode n : probe.creation.getAvailableRaces()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("name", n.name());
            races.add(m);
        }
        out.put("races", races);
        List<Object> classes = new ArrayList<>();
        for (ClassNode n : probe.creation.getAvailableClasses()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("name", n.name());
            classes.add(m);
        }
        out.put("classes", classes);
        List<Object> traits = new ArrayList<>();
        // race/clazz 参数兼容 id 与名称（前端创建页按名称回传）
        RaceNode race = probe.creation.getAvailableRaces().stream()
                .filter(n -> n.id().equals(q.get("race")) || n.name().equals(q.get("race"))).findFirst().orElse(null);
        ClassNode clazz = probe.creation.getAvailableClasses().stream()
                .filter(n -> n.id().equals(q.get("clazz")) || n.name().equals(q.get("clazz"))).findFirst().orElse(null);
        if (race != null && clazz != null) {
            for (TraitDef t : probe.creation.getAvailableTraits(race.data(), clazz.data())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id());
                m.put("name", t.name());
                m.put("description", t.description());
                traits.add(m);
            }
        }
        out.put("traits", traits);
        List<String> difficulties = new ArrayList<>();
        for (DifficultyMode m : DifficultyMode.values()) difficulties.add(m.name());
        out.put("difficulties", difficulties);
        sendJson(resp, 200, out);
    }

    /** POST /api/game/start：一步建档（对齐原 Avalonia「开始冒险」按钮）。 */
    private void handleStart(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "POST")) return;
        JsonValue body = readJson(req);
        if (body == null) { sendError(resp, 400, "请求体需为 JSON {name,race,clazz,trait,difficulty}"); return; }
        DifficultyMode mode = DifficultyMode.NORMAL;
        String diffRaw = body.getString("difficulty", "");
        if (!diffRaw.isEmpty()) {
            try {
                mode = DifficultyMode.valueOf(diffRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendError(resp, 400, "未知难度：" + diffRaw);
                return;
            }
        }
        String name = body.getString("name", "");
        if (name.isEmpty()) name = "旅人";
        GameSession session = new GameSession(dataDir, saveDir, new Random(), mode);
        CommandResult result;
        synchronized (session) {
            session.execute("创建 " + name);
            session.execute(body.getString("race", ""));
            session.execute(body.getString("clazz", ""));
            result = session.execute(body.getString("trait", ""));
        }
        if (session.player == null) {
            sendError(resp, 400, "建档失败：选项不匹配或服务器异常");
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, session);
        Map<String, Object> out = commandToJson(result);
        out.put("sessionId", sessionId);
        sendJson(resp, 200, out);
    }

    /** GET /api/panel/{name}：覆盖层面板数据（只读快照，不改变游戏状态）。 */
    private void handlePanel(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        if (!requireMethod(req, resp, "GET")) return;
        GameSession session = resolveSession(req, resp);
        if (session == null) return;
        String name = pathTail(req.path);
        Map<String, Object> payload;
        synchronized (session) {
            payload = panelPayload(session, name);
        }
        if (payload == null) {
            sendError(resp, 404, "未知面板：" + name);
            return;
        }
        sendJson(resp, 200, payload);
    }

    /** 面板数据构造；未知面板返回 null。 */
    private static Map<String, Object> panelPayload(GameSession s, String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        switch (name) {
            case "char" -> {
                if (s.player == null) { p.put("error", "还没有角色"); return p; }
                p.putAll(s.hud());
                List<Object> equips = new ArrayList<>();
                if (s.equipment != null) {
                    for (Map.Entry<EquipSlot, Equip> e : s.equipment.getAllEquipped().entrySet()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("slot", e.getKey().name());
                        m.put("name", e.getValue().name());
                        m.put("durability", e.getValue().currentDurability() + "/" + e.getValue().maxDurability());
                        equips.add(m);
                    }
                }
                p.put("equipped", equips);
            }
            case "bag" -> {
                if (s.player == null) { p.put("error", "还没有角色"); return p; }
                List<Object> items = new ArrayList<>();
                for (ItemStack st : s.player.inventory().stacks()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", st.def().name());
                    m.put("count", st.count());
                    items.add(m);
                }
                p.put("items", items);
                p.put("gold", s.player.gold());
            }
            case "skill" -> {
                List<String> names = new ArrayList<>();
                if (s.skillTree != null) for (Skill sk : s.skillTree.getUnlockedSkills()) names.add(sk.name());
                p.put("unlocked", names);
            }
            case "quest" -> {
                List<Object> quests = new ArrayList<>();
                for (QuestNode q : s.questCache) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", q.name());
                    m.put("description", q.data().description());
                    m.put("minLevel", q.data().minLevel());
                    quests.add(m);
                }
                p.put("quests", quests);
                p.put("hint", "布告板旁输入「任务」刷新列表");
            }
            case "home" -> {
                p.put("hasHome", s.home != null);
                if (s.home != null) {
                    p.put("level", s.home.level());
                    p.put("buildings", s.home.getBuildings().stream().map(b -> b.name()).collect(Collectors.toList()));
                    p.put("nearHome", s.nearHome());
                }
            }
            case "recipe" -> {
                if (s.crafting != null && s.recipeCache.isEmpty()) s.recipeCache = s.crafting.getKnownRecipes();
                p.put("recipes", s.recipeCache.stream().map(r -> r.name()).collect(Collectors.toList()));
            }
            case "settings" -> {
                p.put("aiAvailable", s.ai.isAvailable());
                p.put("difficulty", s.difficulty.name());
                p.put("stage", "P8");
            }
            case "map" -> {
                // 50x50 地形网格：迷雾未探索 '?'，已探索取生态首字母；特征点另给标记列表
                int w = s.map.width(), h = s.map.height();
                List<Object> rows = new ArrayList<>();
                for (int y = 0; y < h; y++) {
                    StringBuilder sb = new StringBuilder(w);
                    for (int x = 0; x < w; x++) {
                        if (s.map.currentFog().get(x, y).name().equals("UNEXPLORED")) {
                            sb.append('?');
                        } else {
                            sb.append(biomeChar(s.map.currentBiome(new MapPos(x, y))));
                        }
                    }
                    rows.add(sb.toString());
                }
                p.put("cells", rows);
                List<Object> marks = new ArrayList<>();
                for (TerrainFeature f : s.map.features()) {
                    if (s.map.currentFog().get(f.pos().x(), f.pos().y()).name().equals("UNEXPLORED")) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("x", f.pos().x());
                    m.put("y", f.pos().y());
                    m.put("type", f.type().name());
                    m.put("id", f.id());
                    marks.add(m);
                }
                p.put("marks", marks);
                if (s.player != null && s.player.worldPos() != null) {
                    p.put("px", s.player.worldPos().x());
                    p.put("py", s.player.worldPos().y());
                }
                p.put("header", (s.player != null && s.player.worldPos() != null
                        ? "主世界·(" + s.player.worldPos().x() + "," + s.player.worldPos().y() + ")·"
                        : "主世界·")
                        + s.time.display());
                p.put("legend", "一格一场景：地形为名称首字（平/林/沙/苔/沼/山）；怪=怪物，人=NPC，采=采集点，建=建筑，门=副本入口，家=家园；你=玩家位置；留白=未探索");
            }
            case "codex" -> {
                List<Object> monsters = new ArrayList<>();
                for (MonsterTemplate t : s.registries.monsters.getAll()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", t.name());
                    m.put("detail", "战力角色：" + t.combatRole().name());
                    monsters.add(m);
                }
                p.put("monsters", monsters);
                List<Object> items = new ArrayList<>();
                for (ItemDef d : s.registries.items.getAll()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d.name());
                    m.put("detail", (d.description() == null || d.description().isEmpty())
                            ? d.type().name() : d.description());
                    items.add(m);
                }
                p.put("items", items);
                List<Object> recipes = new ArrayList<>();
                for (Recipe r : s.registries.recipes.getAll()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", r.name());
                    m.put("detail", r.materials().size() + " 种材料 → " + r.outputCount() + " 件产出");
                    recipes.add(m);
                }
                p.put("recipes", recipes);
            }
            default -> { return null; }
        }
        return p;
    }

    /** 生态 → 地图格子字母（前端再映射为文字）。 */
    private static char biomeChar(BiomeType b) {
        return switch (b) {
            case PLAINS -> 'P';
            case FOREST -> 'F';
            case DESERT -> 'D';
            case TUNDRA -> 'T';
            case SWAMP -> 'S';
            case MOUNTAIN -> 'M';
        };
    }

    /** 静态托管 frontend/dist；路径穿越防护 + 目录默认 index.html。 */
    private void handleStatic(MiniHttpServer.Req req, MiniHttpServer.Resp resp) throws IOException {
        if (!requireMethod(req, resp, "GET")) return;
        String path = req.path;
        if (path.equals("/")) path = "/index.html";
        Path file = staticDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(staticDir.normalize()) || !Files.isRegularFile(file)) {
            sendError(resp, 404, "资源不存在：" + path);
            return;
        }
        resp.status = 200;
        resp.contentType = mime(file.getFileName().toString());
        resp.body = Files.readAllBytes(file);
    }

    private static String mime(String fileName) {
        if (fileName.endsWith(".html")) return "text/html; charset=utf-8";
        if (fileName.endsWith(".css")) return "text/css; charset=utf-8";
        if (fileName.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (fileName.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    // ==================== 助手 ====================

    /** CommandResult → JSON 载荷：{narration:[{text,kind}], hud:{...}}。 */
    private static Map<String, Object> commandToJson(CommandResult result) {
        List<Object> narration = new ArrayList<>();
        for (NarrationLine line : result.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", line.text());
            m.put("kind", line.kind().name());
            narration.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("narration", narration);
        body.put("hud", result.hud());
        return body;
    }

    /** 查询参数解析（?k=v&k2=v2，值做 URL 解码）。 */
    private static Map<String, String> queryParams(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2) out.put(p[0], urlDecode(p[1]));
            }
        }
        return out;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return s;
        }
    }

    /** /api/save/{slot} 或 /api/load/{slot} 的尾段。 */
    private static String pathTail(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : "";
    }

    /** 解析槽位号；非法回 400 并返回 -1。 */
    private int parseSlot(MiniHttpServer.Resp resp, String tail) {
        int slot;
        try {
            slot = Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            sendError(resp, 400, "槽位号非法：" + tail);
            return -1;
        }
        if (slot < 1 || slot > 10) {
            sendError(resp, 400, "槽位范围为 1~10：" + slot);
            return -1;
        }
        return slot;
    }

    private GameSession resolveSession(MiniHttpServer.Req req, MiniHttpServer.Resp resp) {
        String sessionId = queryParams(req.query).getOrDefault("sessionId", "");
        GameSession session = sessions.get(sessionId);
        if (session == null && sessionId.isEmpty() && sessions.size() == 1)
            session = sessions.values().iterator().next();
        if (session == null) sendError(resp, 404, "会话不存在，请通过 ?sessionId= 指定");
        return session;
    }

    private static JsonValue readJson(MiniHttpServer.Req req) {
        if (req.body.length == 0) return null;
        try {
            return JsonReader.parse(new String(req.body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean requireMethod(MiniHttpServer.Req req, MiniHttpServer.Resp resp, String method) {
        if (req.method.equalsIgnoreCase(method)) return true;
        sendError(resp, 405, "仅支持 " + method);
        return false;
    }

    private static void sendJson(MiniHttpServer.Resp resp, int code, Object payload) {
        resp.status = code;
        resp.contentType = "application/json; charset=utf-8";
        resp.body = JsonWriter.write(payload).getBytes(StandardCharsets.UTF_8);
    }

    private static void sendError(MiniHttpServer.Resp resp, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        sendJson(resp, code, body);
    }

    // ==================== 独立启动入口 ====================

    /** 用法：java com.canglan.api.HttpApiServer <dataDir> <saveDir> [port=8080] [staticDir=frontend/dist] */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("用法: HttpApiServer <dataDir> <saveDir> [port] [staticDir]");
            System.exit(1);
        }
        Path dataDir = Path.of(args[0]);
        Path saveDir = Path.of(args[1]);
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 8080;
        Path staticDir = args.length > 3 ? Path.of(args[3]) : Path.of("frontend", "dist");
        HttpApiServer api = new HttpApiServer(dataDir, saveDir, staticDir);
        api.start(port);
        System.out.println("HttpApiServer listening on http://localhost:" + port);
    }
}

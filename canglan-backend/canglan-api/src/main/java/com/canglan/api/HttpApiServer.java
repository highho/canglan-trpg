package com.canglan.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
import java.util.concurrent.Executors;

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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * HttpApiServer — REST 层（MIGRATION_PLAN §7a，JDK 内置 com.sun.net.httpserver，零外部依赖）。
 * P6 端点子集：
 *   GET  /api/health           探活（含 AI 可用性）
 *   POST /api/game/new         新游戏 → {sessionId}
 *   POST /api/game/command     {sessionId, line} → {narration[], hud}
 *   POST /api/save/{slot}      手动存档到指定槽位
 *   POST /api/load/{slot}      读档
 *   GET  /api/save/slots       槽位列表（?sessionId=xxx）
 * P8 扩展：
 *   GET  /api/game/state       全量叙事日志 + HUD（前端刷新恢复）
 *   GET  /api/panel/{name}     覆盖层面板数据（char/bag/skill/quest/home/recipe/settings）
 *   非 /api 路径               静态托管 frontend/dist（P8 前端产物）
 * P8 对齐原 Avalonia UI：
 *   GET  /api/creation/options 创建页选项（血脉/道路/特质，随选择过滤）
 *   POST /api/game/start       一步建档（name/race/clazz/trait/difficulty）→ 会话 + 完整叙事
 *   panel 补 map（迷雾 50x50 网格）/ codex（怪物/物品/配方图鉴）
 * WS 事件推送延至后续阶段（与前端双向交互同期）。
 */
public final class HttpApiServer {

    private final Path dataDir;
    private final Path saveDir;
    private final Path staticDir;
    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private HttpServer server;

    public HttpApiServer(Path dataDir, Path saveDir) {
        this(dataDir, saveDir, Path.of("frontend", "dist"));
    }

    public HttpApiServer(Path dataDir, Path saveDir, Path staticDir) {
        this.dataDir = dataDir;
        this.saveDir = saveDir;
        this.staticDir = staticDir;
    }

    /** 会话注册表只读视图（冒烟测试用）。 */
    public Map<String, GameSession> sessions() {
        return sessions;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/game/new", this::handleNew);
        server.createContext("/api/game/command", this::handleCommand);
        server.createContext("/api/save/slots", this::handleSlots);
        server.createContext("/api/save/", this::handleSaveSlot);
        server.createContext("/api/load/", this::handleLoadSlot);
        server.createContext("/api/game/state", this::handleState);
        server.createContext("/api/game/start", this::handleStart);
        server.createContext("/api/creation/options", this::handleCreationOptions);
        server.createContext("/api/panel/", this::handlePanel);
        server.createContext("/", this::handleStatic);
        // 守护线程池：stop() 后不阻止 JVM 退出
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "canglan-http-worker");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    /** 实际监听端口（传 0 启动时由系统分配，冒烟测试用）。 */
    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    // ==================== 端点实现 ====================

    private void handleHealth(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "GET")) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        // AI 可用性：取任一会话的 AiClient 状态（无会话时视为不可用）
        boolean aiUp = sessions.values().stream().anyMatch(gs -> gs.ai.isAvailable());
        body.put("aiAvailable", aiUp);
        body.put("sessions", sessions.size());
        sendJson(ex, 200, body);
    }

    private void handleNew(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "POST")) return;
        JsonValue req = readJson(ex);
        DifficultyMode mode = DifficultyMode.NORMAL;
        String raw = req == null ? "" : req.getString("difficulty", "");
        if (!raw.isEmpty()) {
            try {
                mode = DifficultyMode.valueOf(raw.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, "未知难度：" + raw);
                return;
            }
        }
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new GameSession(dataDir, saveDir, new Random(), mode));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("difficulty", mode.name());
        sendJson(ex, 200, body);
    }

    private void handleCommand(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "POST")) return;
        JsonValue req = readJson(ex);
        if (req == null) { sendError(ex, 400, "请求体需为 JSON 对象 {sessionId, line}"); return; }
        GameSession session = sessions.get(req.getString("sessionId", ""));
        if (session == null) { sendError(ex, 404, "会话不存在，请先 POST /api/game/new"); return; }
        String line = req.getString("line", "");
        CommandResult result;
        synchronized (session) {
            result = session.execute(line);
        }
        sendJson(ex, 200, commandToJson(result));
    }

    private void handleSaveSlot(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "POST")) return;
        GameSession session = resolveSession(ex);
        if (session == null) return;
        int slot = parseSlot(ex, pathTail(ex));
        if (slot < 0) return;
        CommandResult result;
        synchronized (session) {
            session.selectedSlot = slot;
            result = session.execute("存档");
        }
        sendJson(ex, 200, commandToJson(result));
    }

    private void handleLoadSlot(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "POST")) return;
        GameSession session = resolveSession(ex);
        if (session == null) return;
        int slot = parseSlot(ex, pathTail(ex));
        if (slot < 0) return;
        CommandResult result;
        synchronized (session) {
            session.selectedSlot = slot;
            result = session.execute("读档");
        }
        sendJson(ex, 200, commandToJson(result));
    }

    private void handleSlots(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "GET")) return;
        // ?sessionId=xxx；缺省时仅允许只有一个会话
        String query = ex.getRequestURI().getQuery();
        String sessionId = "";
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].equals("sessionId")) sessionId = p[1];
            }
        }
        GameSession session = sessionId.isEmpty() && sessions.size() == 1
                ? sessions.values().iterator().next()
                : sessions.get(sessionId);
        if (session == null) { sendError(ex, 404, "会话不存在，请通过 ?sessionId= 指定"); return; }
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slots", slots);
        sendJson(ex, 200, body);
    }

    // ==================== P8：状态恢复 / 面板 / 静态托管 ====================

    /** GET /api/game/state：全量叙事日志 + HUD（前端刷新恢复）。 */
    private void handleState(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "GET")) return;
        GameSession session = resolveSession(ex);
        if (session == null) return;
        Map<String, Object> body;
        synchronized (session) {
            List<Object> log = new ArrayList<>();
            for (NarrationLine line : session.log()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("text", line.text());
                m.put("kind", line.kind().name());
                log.add(m);
            }
            body = new LinkedHashMap<>();
            body.put("log", log);
            body.put("hud", session.hud());
        }
        sendJson(ex, 200, body);
    }

    /** GET /api/creation/options：创建页选项（血脉/道路/特质，随 race/clazz 过滤，对齐原 Avalonia 创建页）。 */
    private void handleCreationOptions(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "GET")) return;
        GameSession probe = new GameSession(dataDir, saveDir, new Random(), DifficultyMode.NORMAL);
        Map<String, String> q = queryParams(ex);
        Map<String, Object> body = new LinkedHashMap<>();
        List<Object> races = new ArrayList<>();
        for (RaceNode n : probe.creation.getAvailableRaces()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("name", n.name());
            races.add(m);
        }
        body.put("races", races);
        List<Object> classes = new ArrayList<>();
        for (ClassNode n : probe.creation.getAvailableClasses()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("name", n.name());
            classes.add(m);
        }
        body.put("classes", classes);
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
        body.put("traits", traits);
        List<String> difficulties = new ArrayList<>();
        for (DifficultyMode m : DifficultyMode.values()) difficulties.add(m.name());
        body.put("difficulties", difficulties);
        sendJson(ex, 200, body);
    }

    /** POST /api/game/start：一步建档（对齐原 Avalonia「开始冒险」按钮）。 */
    private void handleStart(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "POST")) return;
        JsonValue req = readJson(ex);
        if (req == null) { sendError(ex, 400, "请求体需为 JSON {name,race,clazz,trait,difficulty}"); return; }
        DifficultyMode mode = DifficultyMode.NORMAL;
        String diffRaw = req.getString("difficulty", "");
        if (!diffRaw.isEmpty()) {
            try {
                mode = DifficultyMode.valueOf(diffRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, "未知难度：" + diffRaw);
                return;
            }
        }
        String name = req.getString("name", "");
        if (name.isEmpty()) name = "旅人";
        GameSession session = new GameSession(dataDir, saveDir, new Random(), mode);
        CommandResult result;
        synchronized (session) {
            session.execute("创建 " + name);
            session.execute(req.getString("race", ""));
            session.execute(req.getString("clazz", ""));
            result = session.execute(req.getString("trait", ""));
        }
        if (session.player == null) {
            sendError(ex, 400, "建档失败：选项不匹配或服务器异常");
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, session);
        Map<String, Object> body = commandToJson(result);
        body.put("sessionId", sessionId);
        sendJson(ex, 200, body);
    }

    /** GET /api/panel/{name}：覆盖层面板数据（只读快照，不改变游戏状态）。 */
    private void handlePanel(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "GET")) return;
        GameSession session = resolveSession(ex);
        if (session == null) return;
        String name = pathTail(ex);
        Map<String, Object> payload;
        synchronized (session) {
            payload = panelPayload(session, name);
        }
        if (payload == null) {
            sendError(ex, 404, "未知面板：" + name);
            return;
        }
        sendJson(ex, 200, payload);
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
                    p.put("buildings", s.home.getBuildings().stream().map(b -> b.name()).toList());
                    p.put("nearHome", s.nearHome());
                }
            }
            case "recipe" -> {
                if (s.crafting != null && s.recipeCache.isEmpty()) s.recipeCache = s.crafting.getKnownRecipes();
                p.put("recipes", s.recipeCache.stream().map(r -> r.name()).toList());
            }
            case "settings" -> {
                p.put("aiAvailable", s.ai.isAvailable());
                p.put("difficulty", s.difficulty.name());
                p.put("stage", "P8");
            }
            case "map" -> {
                // 开拓者式 50x50 地形网格：迷雾未探索 '?'，已探索取生态首字母；特征点另给标记列表
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

    /** 生态 → 地图格子字母（前端再映射底色）。 */
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
    private void handleStatic(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!requireMethod(ex, "GET")) return;
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = staticDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(staticDir.normalize()) || !Files.isRegularFile(file)) {
            sendError(ex, 404, "资源不存在：" + path);
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().add("Content-Type", mime(file.getFileName().toString()));
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
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

    /** 查询参数解析（?k=v&k2=v2）。 */
    private static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2) out.put(p[0], p[1]);
            }
        }
        return out;
    }

    /** /api/save/{slot} 或 /api/load/{slot} 的尾段。 */
    private static String pathTail(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : "";
    }

    /** 解析槽位号；非法回 400 并返回 -1。 */
    private int parseSlot(HttpExchange ex, String tail) throws IOException {
        int slot;
        try {
            slot = Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            sendError(ex, 400, "槽位号非法：" + tail);
            return -1;
        }
        if (slot < 1 || slot > 10) {
            sendError(ex, 400, "槽位范围为 1~10：" + slot);
            return -1;
        }
        return slot;
    }

    private GameSession resolveSession(HttpExchange ex) throws IOException {
        String sessionId = "";
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].equals("sessionId")) sessionId = p[1];
            }
        }
        GameSession session = sessions.get(sessionId);
        if (session == null && sessionId.isEmpty() && sessions.size() == 1)
            session = sessions.values().iterator().next();
        if (session == null) sendError(ex, 404, "会话不存在，请通过 ?sessionId= 指定");
        return session;
    }

    private static JsonValue readJson(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length == 0) return null;
        try {
            return JsonReader.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean requireMethod(HttpExchange ex, String method) throws IOException {
        if (ex.getRequestMethod().equalsIgnoreCase(method)) return true;
        sendError(ex, 405, "仅支持 " + method);
        return false;
    }

    /** CORS 预检：P8 前端直连需要；同时放行所有来源（本地单机服务）。 */
    private static boolean preflight(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        if (ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return true;
        }
        return false;
    }

    private static void sendJson(HttpExchange ex, int code, Object payload) throws IOException {
        byte[] bytes = JsonWriter.write(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, int code, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        sendJson(ex, code, body);
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

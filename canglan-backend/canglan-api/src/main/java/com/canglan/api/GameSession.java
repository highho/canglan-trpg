package com.canglan.api;

import java.util.stream.Collectors;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.canglan.ai.AiClient;
import com.canglan.ai.AiClients;
import com.canglan.core.eventbus.EventBusImpl;
import com.canglan.core.graph.ClassNode;
import com.canglan.core.graph.QuestNode;
import com.canglan.core.graph.RaceNode;
import com.canglan.core.tag.TagConditionParser;
import com.canglan.core.tag.TagFactory;
import com.canglan.data.bootstrap.Registries;
import com.canglan.data.bootstrap.RegistryInitializer;
import com.canglan.data.buff.BuffFactory;
import com.canglan.data.craft.GatherableResource;
import com.canglan.data.craft.Recipe;
import com.canglan.data.monster.MonsterTemplate;
import com.canglan.data.item.ItemStack;
import com.canglan.data.npc.NpcDef;
import com.canglan.data.shop.ShopDef;
import com.canglan.data.skill.CooldownManager;
import com.canglan.data.skill.SkillTree;
import com.canglan.data.trait.TraitDef;
import com.canglan.save.GameLoader;
import com.canglan.save.GameState;
import com.canglan.save.SaveData;
import com.canglan.save.SaveManager;
import com.canglan.world.DifficultyMode;
import com.canglan.world.FeatureType;
import com.canglan.world.GameTime;
import com.canglan.world.MapPos;
import com.canglan.world.SurvivalManager;
import com.canglan.world.TerrainFeature;
import com.canglan.world.WorldMap;
import com.canglan.world.WorldPopulator;
import com.canglan.world.craft.CraftingSystem;
import com.canglan.world.craft.GatherPoint;
import com.canglan.world.creation.CharacterCreation;
import com.canglan.world.effect.EffectEngine;
import com.canglan.world.equipment.EquipmentManager;
import com.canglan.world.home.HomeBase;
import com.canglan.world.monster.MonsterFactory;
import com.canglan.world.npc.NpcFactory;
import com.canglan.world.npc.dialogue.DialogueTreeLoader;
import com.canglan.world.quest.AdventureGuild;
import com.canglan.world.shop.Shop;
import com.canglan.world.social.FactionSystem;
import com.canglan.world.social.ReputationSystem;
import com.canglan.world.unit.Unit;

/**
 * GameSession — 一局游戏的完整状态编排（对应 C# MainViewModel 的状态与流程部分）。
 * 指令解析/分发在 {@link CommandDispatcher}；铁律不变：所有游戏逻辑走规则系统。
 * P6 简化：随机遭遇/成就/图鉴/生涯统计不迁移；AI 自由对话延至 P7。
 */
public final class GameSession {

    static final int NEARBY_RANGE = 4;   // 怪物/采集点的可行动半径
    static final int NPC_RANGE = 4;      // NPC 可交谈半径（新手村范围）
    static final int WORLD_SIZE = 50;
    static final MapPos VILLAGE = new MapPos(25, 25);

    // ==================== 世界基础设施（显式注入，替代 C# 静态单例） ====================
    final Registries registries;
    final EventBusImpl bus;
    final EffectEngine effectEngine;
    final TagFactory tagFactory;
    final WorldMap map;
    final GameTime time;
    final SurvivalManager survivalManager;
    final SaveManager saveManager;
    final GameLoader gameLoader;
    final ReputationSystem reputation;
    final FactionSystem factions;
    final NpcFactory npcFactory;
    final MonsterFactory monsterFactory;
    final CharacterCreation creation;
    final AdventureGuild guild;
    final Random rng;
    final DifficultyMode difficulty;
    final AiClient ai;   // P7：探活失败自动降级为 NullAiClient（规则兜底）

    // ==================== 核心世界（对应 C# MainViewModel 字段） ====================
    Unit player;
    EquipmentManager equipment;
    CraftingSystem crafting;
    HomeBase home;
    SkillTree skillTree;
    CooldownManager cooldowns;
    final Map<String, Unit> npcInstances = new HashMap<>();
    final List<Unit> companions = new ArrayList<>();
    final Map<String, Shop> shopInstances = new HashMap<>();
    final Map<String, GatherPoint> gatherPoints = new HashMap<>();   // 资源id → 采集点实例（储量/冷却持久）
    List<QuestNode> questCache = new ArrayList<>();
    List<Recipe> recipeCache = new ArrayList<>();
    int buildX = 1, buildY = 1;
    String lastTalkNpcId;
    int stepCount;
    int selectedSlot = 1;

    // ==================== 创建流程状态机：0=未开始 1=种族 2=职业 3=特质 ====================
    int creationStage;
    String creationName = "旅人";
    List<RaceNode> creationRaces = new ArrayList<>();
    List<ClassNode> creationClasses = new ArrayList<>();
    List<TraitDef> creationTraits = new ArrayList<>();
    RaceNode pickedRace;
    ClassNode pickedClass;

    // ==================== 叙事流缓冲（每次 execute 重置）与全量日志（前端刷新恢复） ====================
    final List<NarrationLine> lines = new ArrayList<>();
    private final List<NarrationLine> log = new ArrayList<>();
    private static final int LOG_LIMIT = 500;
    private final CommandDispatcher dispatcher;

    public GameSession(Path dataDir, Path saveDir, Random rng, DifficultyMode difficulty) {
        this.rng = rng != null ? rng : new Random();
        this.difficulty = difficulty != null ? difficulty : DifficultyMode.NORMAL;
        // AI 接入：外部服务 -Dcanglan.ai.url（探活失败/空值 → 内嵌管线）；"off" 显式禁用；
        // 内嵌管线可选接 LLM：-Dcanglan.ai.llm.url（OpenAI 兼容端点）
        this.ai = AiClients.connect(System.getProperty("canglan.ai.url", "http://localhost:8000"), this.rng, saveDir);
        this.registries = RegistryInitializer.initialize(dataDir);
        this.bus = new EventBusImpl();
        this.effectEngine = new EffectEngine(new BuffFactory(registries.buffs));
        this.tagFactory = new TagFactory(registries.tags);
        this.time = new GameTime();

        this.map = new WorldMap(WORLD_SIZE, WORLD_SIZE);
        WorldPopulator.populate(map,
                registries.npcs.getAll().stream().map(NpcDef::id).collect(Collectors.toList()),
                registries.monsters.getAll().stream().map(MonsterTemplate::id).collect(Collectors.toList()),
                registries.resources.getAll().stream().map(GatherableResource::id).collect(Collectors.toList()));

        this.survivalManager = new SurvivalManager(bus);
        this.saveManager = new SaveManager(saveDir);
        this.gameLoader = new GameLoader(saveManager, registries.raceGraph, registries.classGraph,
                tagFactory, effectEngine, bus, registries.items, WORLD_SIZE, WORLD_SIZE, time);
        this.npcFactory = new NpcFactory(tagFactory, effectEngine, bus, registries.items,
                registries.npcs, new DialogueTreeLoader(new TagConditionParser()));
        this.monsterFactory = new MonsterFactory(tagFactory, effectEngine, bus,
                registries.items, registries.monsters, this.rng);
        this.reputation = new ReputationSystem();
        this.factions = new FactionSystem();
        this.guild = new AdventureGuild(registries.questGraph, reputation);
        this.creation = new CharacterCreation(registries.raceGraph, registries.classGraph,
                registries.traits, tagFactory, effectEngine, bus, registries.items, null);
        this.dispatcher = new CommandDispatcher(this);
    }

    // ==================== 指令入口 ====================

    /** 执行一行指令：全角空格归一化 → 分发 → 收集叙事行 + HUD 快照。 */
    public CommandResult execute(String rawInput) {
        lines.clear();
        String raw = rawInput == null ? "" : rawInput.replace('　', ' ').trim();
        if (!raw.isEmpty()) {
            narrate("指令：" + raw, NarrationKind.INPUT);
            try {
                dispatcher.dispatch(raw);
            } catch (Exception ex) {
                narrate("指令执行异常：" + ex.getMessage(), NarrationKind.ERROR);
            }
        }
        return new CommandResult(new ArrayList<>(lines), hud());
    }

    void narrate(String text, NarrationKind kind) {
        NarrationLine line = new NarrationLine(text, kind);
        lines.add(line);
        log.add(line);
        if (log.size() > LOG_LIMIT) log.subList(0, log.size() - LOG_LIMIT).clear();
    }

    /** 全量叙事日志快照（GET /api/game/state 用）。 */
    public List<NarrationLine> log() {
        return new ArrayList<>(log);
    }

    // ==================== 空间/状态助手（契约铁律在此收口） ====================

    boolean ensurePlayer() {
        if (player != null) return true;
        narrate("你还没有角色。输入「创建 你的名字」开始建档。", NarrationKind.SYSTEM);
        return false;
    }

    /** 玩家坐标附近是否存在指定类型的地形物（Id 匹配）。 */
    boolean isNearby(FeatureType type, String id, int range) {
        if (player == null || player.worldPos() == null) return false;
        for (TerrainFeature f : map.findNearby(player.worldPos(), range))
            if (f.type() == type && f.id().equals(id)) return true;
        return false;
    }

    /** 是否在家园范围内（家园相关行动的空间门槛）。 */
    boolean nearHome() {
        return home != null && player != null && player.worldPos() != null
                && player.worldPos().distanceTo(home.position()) <= NEARBY_RANGE + 1;
    }

    /** 从玩家当前位置指向目标坐标的方位描述（如「东北方向（约 7 步）」）。 */
    String directionTo(MapPos to) {
        int dx = to.x() - player.worldPos().x();
        int dy = to.y() - player.worldPos().y();
        String ns = dy < 0 ? "北" : dy > 0 ? "南" : "";
        String ew = dx > 0 ? "东" : dx < 0 ? "西" : "";
        String dir = ns + ew;
        int dist = (int) Math.ceil(player.worldPos().distanceTo(to));
        return dir.isEmpty() ? "就在脚下" : dir + "方向（约 " + dist + " 步）";
    }

    /** NPC 实例缓存（对应 C# GetNpcInstance）。 */
    Unit getNpcInstance(String npcId) {
        Unit npc = npcInstances.get(npcId);
        if (npc == null) {
            npc = npcFactory.create(npcId);
            npcInstances.put(npcId, npc);
        }
        return npc;
    }

    /** 商店实例缓存（货架库存跨指令持久）。 */
    Shop getShopInstance(ShopDef def) {
        return shopInstances.computeIfAbsent(def.id(), id -> new Shop(def, registries.items));
    }

    String locationName() {
        return player == null ? "新手村"
                : "（" + player.worldPos().x() + "," + player.worldPos().y() + "）的荒野";
    }

    // ==================== 存档：采集当前状态 ====================

    SaveData captureCurrent() {
        Map<String, Integer> affinities = new HashMap<>();
        for (Map.Entry<String, Unit> kv : npcInstances.entrySet())
            affinities.put(kv.getKey(), kv.getValue().affinity());
        return SaveManager.capture(player, companions, affinities, reputation.toSaveMap(),
                new HashMap<>(), new HashMap<>(),
                stepCount, locationName(),
                time.day(), time.hour(),
                map.currentLayer().saveName(), map.currentBiome(player.worldPos()).name(),
                List.of(), Set.of(),
                equipment != null ? equipment.getEquippedMap() : Map.of(),
                stepCount, map.currentFog().exportRows());
    }

    // ==================== 读档核心：恢复全部状态 ====================

    boolean doLoad(int slot) {
        try {
            SaveData data = saveManager.load(slot);
            if (data == null) {
                narrate("存档位 " + slot + " 是空的。", NarrationKind.SYSTEM);
                return false;
            }
                        // 不清 lines：保留 execute 层的「指令：读档」回显行（load 前无其它叙事）
            GameState state = gameLoader.load(slot);
            selectedSlot = slot;
            player = state.player();
            equipment = new EquipmentManager(player, bus, registries.setBonuses, registries.equips);
            equipment.restoreEquipped(data.equippedItems.values());
            companions.clear();
            companions.addAll(state.companions());
            crafting = new CraftingSystem(player, bus, registries.recipes);
            home = new HomeBase(VILLAGE, 8, 8, bus, player);
            cooldowns = new CooldownManager(bus);
            // 恢复地图层级与迷雾
            map.switchLayer(state.map().currentLayer());
            map.currentFog().importRows(state.map().currentFog().exportRows());
            // 恢复 NPC 好感度（清掉旧实例，避免残留）
            npcInstances.clear();
            lastTalkNpcId = null;
            for (Map.Entry<String, Integer> kv : state.npcAffinities().entrySet())
                getNpcInstance(kv.getKey()).setAffinity(kv.getValue());
            reputation.loadFrom(state.factionReputations());
            stepCount = state.stepCount();
            ClassNode cls = player.currentClass();
            skillTree = cls != null && cls.skillTreeRoot() != null
                    ? registries.skillTrees.tryGet(cls.skillTreeRoot()) : null;
            narrate("记忆涌回——" + player.name() + "，Lv" + player.level()
                    + "，金币 " + player.gold() + "。旅途继续。", NarrationKind.SYSTEM);
            return true;
        } catch (Exception ex) {
            narrate("读档失败：" + ex.getMessage(), NarrationKind.ERROR);
            return false;
        }
    }

    // ==================== HUD 快照（REST 前端渲染用） ====================

    Map<String, Object> hud() {
        Map<String, Object> hud = new LinkedHashMap<>();
        hud.put("hasPlayer", player != null);
        hud.put("creating", creationStage);   // 数值阶段（0=未创建），与前端 Hud 接口契约一致
        hud.put("time", time.display());
        hud.put("difficulty", difficulty.name());
        hud.put("stepCount", stepCount);
        if (player != null) {
            hud.put("name", player.name());
            hud.put("level", player.level());
            hud.put("race", player.currentRace() != null ? player.currentRace().name() : "");
            hud.put("clazz", player.currentClass() != null ? player.currentClass().name() : "");
            hud.put("hp", (int) player.stats().hp());
            hud.put("maxHp", player.maxHp());
            hud.put("exp", player.exp());
            hud.put("gold", player.gold());
            hud.put("x", player.worldPos() != null ? player.worldPos().x() : 0);
            hud.put("y", player.worldPos() != null ? player.worldPos().y() : 0);
            hud.put("hunger", player.survival().hunger());
            hud.put("thirst", player.survival().thirst());
            hud.put("temperature", player.survival().temperature());
            hud.put("sanity", player.survival().sanity());
            hud.put("atk", Math.round(player.getStat("atk")));
            hud.put("def", Math.round(player.getStat("def")));
            hud.put("spd", Math.round(player.getStat("spd")));
            hud.put("weight", player.inventory().totalWeight() + "/" + player.carryCapacity());
            hud.put("overloaded", player.isOverloaded());
            hud.put("tags", new ArrayList<>(player.activeTagIds()));
            hud.put("companions", companions.stream().map(Unit::name).collect(Collectors.toList()));
            hud.put("directions", directionsPayload());
            hud.put("nearby", nearbyPayload());
            hud.put("quickBar", quickBarPayload());
        }
        if (home != null) {
            hud.put("homeLevel", home.level());
            hud.put("homeBuildings", home.getBuildings().stream()
                    .map(b -> b.name()).collect(Collectors.toList()));
        }
        return hud;
    }

    /** 四邻地形（移动键盘用；北/东/南/西，对齐原 Avalonia Directions[0..3]）。 */
    private List<Map<String, Object>> directionsPayload() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (player == null || player.worldPos() == null) return out;
        MapPos pos = player.worldPos();
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        String[] names = {"北", "东", "南", "西"};
        for (int i = 0; i < 4; i++) {
            int x = pos.x() + dirs[i][0];
            int y = pos.y() + dirs[i][1];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dir", names[i]);
            boolean edge = x < 0 || y < 0 || x >= map.width() || y >= map.height();
            m.put("terrain", edge ? "边界" : map.currentBiome(new MapPos(x, y)).name());
            out.add(m);
        }
        return out;
    }

    /** 附近怪物/NPC（NEARBY_RANGE 内刷新点，附近卡片区用）。 */
    private Map<String, Object> nearbyPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> monsters = new ArrayList<>();
        List<String> npcs = new ArrayList<>();
        if (player != null && player.worldPos() != null) {
            for (TerrainFeature f : map.findNearby(player.worldPos(), NEARBY_RANGE)) {
                if (f.type() == FeatureType.MONSTER_SPAWN) {
                    MonsterTemplate t = registries.monsters.tryGet(f.id());
                    if (t != null) monsters.add(t.name());
                } else if (f.type() == FeatureType.NPC_SPAWN) {
                    NpcDef d = registries.npcs.tryGet(f.id());
                    if (d != null) npcs.add(d.name());
                }
            }
        }
        out.put("monsters", monsters);
        out.put("npcs", npcs);
        return out;
    }

    /** 快捷栏（开拓者式 7 格：背包前 7 种物品，点击「使用 名称」）。 */
    private List<Map<String, Object>> quickBarPayload() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (player == null) return out;
        for (ItemStack st : player.inventory().stacks()) {
            if (out.size() >= 7) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", st.def().name());
            m.put("count", st.count());
            out.add(m);
        }
        return out;
    }
}

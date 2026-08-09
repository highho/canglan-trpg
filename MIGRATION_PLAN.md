# 苍岚大陆 · 跨技术栈迁移方案

> 参考材料：[PROJECT_CORE.md](PROJECT_CORE.md)。本方案为规划文档，迁移实施期间**不删除**任何旧 C#/.NET/Avalonia 文件。

## 0. 目标架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│  浏览器（纯 HTML + CSS + TypeScript，无框架）                      │
│  index.html → 叙事引擎 / 选项卡片 / 覆盖层面板 / 页面状态机         │
└──────────────────────────────┬──────────────────────────────────┘
                               │ REST (指令/存档/面板) + WebSocket (事件推送)
┌──────────────────────────────┴──────────────────────────────────┐
│  Java 后端（Maven 多模块，Spring Boot 或 Quarkus）                 │
│  canglan-backend                                                  │
│  ├── canglan-core    (标签/图/Unit/EventBus/战斗，零 IO 依赖)      │
│  ├── canglan-data    (JSON 配置加载 + 注册表)                      │
│  ├── canglan-world   (地图/迷雾/生存/遭遇)                         │
│  ├── canglan-save    (存档哲学：源头数据 + SQLite 持久化)           │
│  ├── canglan-ai-client (Python AI 调用 + 降级)                    │
│  └── canglan-api     (HTTP/WS 接入层，编排上述模块)                 │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTP + JSON（推荐），超时 3s
┌──────────────────────────────┴──────────────────────────────────┐
│  Python AI 服务（LangGraph，FastAPI 暴露）                         │
│  NPC 自由对话 / 二层记忆召回 / 任务生成 / 传闻生成 / 行为建议         │
└─────────────────────────────────────────────────────────────────┘
数据层：data/*.json（静态配置，只读） + game.db（SQLite，运行时状态/存档/记忆）
```

## 1. 新旧架构对照表

| 维度 | 旧（C#/.NET/Avalonia） | 新（Java + Web + Python） |
|---|---|---|
| 核心逻辑 | GameCore（net8.0） | canglan-core + data + world（Java 17+） |
| UI | Avalonia AXAML + MVVM | 原生 HTML/CSS/TS，DOM 事件驱动 |
| 指令解析 | MainViewModel.ExecuteCommandAsync | Java 后端 CommandDispatcher（前端不再解析指令） |
| 事件解耦 | EventBusImpl（同步） | EventBus 接口 + Disruptor/简单同步实现 |
| 存档 | JSON 文件 save_{slot}.json | SQLite saves 表（仍是源头数据 JSON 序列化） |
| AI | LLamaSharp 直连 GGUF / Ollama | Python LangGraph 服务（HTTP） |
| AI 降级 | NullAiService → 规则对话 | AiClient 超时/异常 → RuleFallbackService |
| 数据配置 | data/*.json + csproj 拷贝 | data/*.json（classpath 或外部目录） |
| 构建 | dotnet / aapt2 | Maven + npm(tsc) + uv/pip |
| 部署 | win-x64 自包含 + APK | jar + 静态文件 + Python 进程 |

## 2. 后端实现（Java + Maven）

### 2.1 Maven 多模块结构

```
canglan-backend/                     # parent pom（管理依赖版本）
├── pom.xml
├── canglan-core/                    # 纯领域模型，零框架依赖（可单测）
│   ├── tag/          TagDef, TagRegistry, TagCondition(接口), TagConditionParser,
│   │                 TagFactory, TagEvolutionRegistry
│   ├── effect/       EffectDef, EffectParser, EffectEngine
│   ├── graph/        GraphEngine<T>, GraphLoader, RaceData/ClassData/QuestData
│   ├── unit/         Unit, BehaviorPool, EmotionSystem, GridPos
│   ├── eventbus/     EventBus(接口), EventBusImpl, Event, EventTypes, Subscription
│   ├── battle/       BattleManager, GridSystem, BattleAI, DamageCalculator
│   ├── buff/ skill/ item/ equipment/ craft/ home/ social/ ally/ stats/
│   └── GameWorld.java              # 组装入口（对应 Bootstrap）
├── canglan-data/                    # JSON 配置加载
│   ├── ConfigLoader, JsonDataLoader
│   └── RegistryInitializer          # 按 Bootstrap 顺序初始化全部注册表
├── canglan-world/                   # WorldMap, FogOfWar, Survival, GameTime, RandomEncounter
├── canglan-save/                    # SaveManager, GameLoader, AutoSaveTrigger, DeathHandler
│   └── persistence/  SqliteSaveStore（SQLite 适配，替代文件）
├── canglan-ai-client/               # AiClient(接口), LangGraphHttpClient, RuleFallbackService
│   └── AiCircuitBreaker             # 熔断：连续失败 N 次后短路，避免阻塞
└── canglan-api/                     # Spring Boot 应用入口
    ├── rest/         GameController, SaveController, AiController
    ├── ws/           GameEventWebSocketHandler（推送事件给前端）
    ├── command/      CommandDispatcher（32 个中文指令 → GameCore 调用）
    └── session/      GameSessionManager（每玩家一个 GameWorld 实例）
```

**依赖方向**：api → (save, world, ai-client, data) → core。core 不得依赖任何上层模块（保持零 IO 依赖，等价旧 GameCore 的纯净性）。

### 2.2 核心类设计映射（C# → Java）

| C# 概念 | Java 对应 | 说明 |
|---|---|---|
| `record Event(type, source, payload)` | `record Event(String type, Object source, Map<String,Object> payload)` | Java 17 record |
| `IEventBus` | `interface EventBus` | 同名语义；`SubscribeWithOwner` → `subscribeWithOwner(type, callback, owner)` |
| `EventBusImpl`（ConcurrentDictionary） | 同步实现 + `ConcurrentHashMap` | 保持同步发射语义 |
| `TagRegistry`（静态 Instance） | `TagRegistry` + `RegistryHolder`（显式注入替代静态单例） | **避免静态单例**：通过 GameWorld 传递，解决 Java 静态初始化顺序问题 |
| `TagConditionParser`（字符串 DSL） | 接口 `TagCondition` + `TagConditionParser` | 解析 `has:x`、`tier:x>=2` 等 DSL，产出 `Predicate<Unit>` 或自定义 `evaluate(ctx)` |
| `EffectParser → EffectEngine` | 同上，效果为不可变 `EffectDef` 列表 | |
| `GraphEngine<T>` 泛型图 | `GraphEngine<N extends GraphNode>` | races/classes/quests 三实例 |
| `Unit`（315 行统一模型） | `class Unit` | 字段对齐：role/behaviorPool/stats/survival/inventory/worldPos/affinity |
| `RecalculateTags()` 无状态重建 | `recalculateTags()` | **核心契约**：先 `eventBus.unsubscribeAll(this)` → 重建 ActiveTags → 重新订阅 |
| `WorldMap` + `FogOfWar` | 同名 | 迷雾导出 `List<FogRow>` → SQLite TEXT 列 |
| `SaveManager`（文件 JSON） | `SaveManager` + `SaveStore`(接口) + `SqliteSaveStore` | 接口抽象便于测试用内存实现 |
| `GameLoader.Load` | `GameLoader.load(slot)` | RestoreIdentity → recalculateTags 全量重建 |
| `AutoSaveTrigger` | 订阅 EventBus 同名事件 | |
| `BattleManager` + `AllyActionSelector` 钩子 | 同名；钩子改为 `Function<Unit, BattleAction>` | |
| `AiContext.Create`（三模式降级） | `AiClientFactory.create(settings)` | 返回 `AiClient` 或 `NullAiClient` |
| `AiMemoryManager`（二层记忆） | `MemoryManager`：`IndividualMemory`(每 NPC ≤60) + `GroupMemory` | 记忆标签与 Unit 标签分离 |
| `async Task` | `CompletableFuture<T>` | EventBus 保持同步；仅 AI 调用异步 |

### 2.3 JSON 配置读取方案

- **库选型**：Jackson（`ObjectMapper`）或 Gson；推荐 Jackson + `@JsonCreator` 不可变 POJO。
- **加载入口**：`RegistryInitializer.init(dataDir)` 严格按 PROJECT_CORE.md §3 的 11 步顺序执行：
  1. AiClient 探测（不阻塞）→ 2. tags.json + items.json（**ItemRegistry 必须先于 Unit**）→ 3. buffs/equipments/setBonuses → 4. skills/skillTrees → 5. races→classes→quests → 6. monsters/resources/recipes/buildings/npcs/traits/tag_affinity/bond_skills → 7. achievements/shops → 8. WorldMap+PopulateWorld（固定种子 20260804）→ 9. SaveManager → 10. 工厂 → 11. 长线系统。
- **校验**：加载时 fail-fast——未知标签分类/来源直接抛异常（对应旧代码 `throw new ArgumentException`）；另提供 `mvn -pl canglan-data test` 跑数据完整性校验（引用完整性：quests 引用的标签必须存在于 tags.json）。
- **data 目录位置**：classpath `data/`（打包时拷贝）或启动参数 `-Dcanglan.data.dir=...`，二者取先外部目录。

### 2.4 SQLite 使用边界

| 用途 | 放 SQLite？ | 说明 |
|---|---|---|
| 静态配置（tags/races/...） | ❌ 不用 | 保持 JSON，启动加载入内存注册表 |
| 存档槽位（10 个） | ✅ | saves 表，value 为源头数据 JSON |
| AI 二层记忆 | ✅ | memories 表（个体/群体），支持索引查询 |
| 生涯统计/成就进度/图鉴 | ✅ | stats/codex 表 |
| 运行时会话状态 | ❌ | 内存中（GameSession），仅定期快照到 saves |
| 索引查询（图鉴搜索/成就过滤） | ✅ | SQLite FTS5 或 LIKE 索引 |

**Java 访问方式**：HikariCP 连接池 + JDBC（单写多读）；或更轻量用 `sqlite-jdbc` + 手写 DAO。事务边界：存档写入 = 单事务；AI 记忆批量写入 = 单事务。**不使用 ORM**（Hibernate 过重），推荐 Spring JDBC Template 或 jOOQ。

## 3. 前端实现（纯 HTML + CSS + TypeScript）

### 3.1 页面结构

```
frontend/
├── index.html              # 单页应用壳（SPA 但不用框架路由）
├── css/
│   ├── base.css            # 极简黑白灰文字风、字体、变量
│   ├── narration.css       # 叙事区（滚动日志、行着色）
│   ├── cards.css           # 底部大卡片选项
│   ├── overlay.css         # 覆盖层面板 + 遮罩
│   └── links.css           # 轻薄文字链接（角色/行囊/技能…）
├── ts/
│   ├── main.ts             # 入口：页面状态机驱动
│   ├── pages/
│   │   ├── start.ts        # 开始页
│   │   ├── save-select.ts  # 存档选择（10 槽位）
│   │   ├── creation.ts     # 角色创建（种族→职业→特质 3 步）
│   │   └── game.ts         # 游戏主页（叙事 + 选项 + 链接）
│   ├── overlay/
│   │   ├── overlay.ts      # 覆盖层状态机（11 面板）
│   │   └── panels/         # char.ts bag.ts skill.ts craft.ts quest.ts
│   │                       # home.ts map.ts codex.ts shop.ts forge.ts settings.ts
│   ├── state/
│   │   ├── store.ts        # 极简发布订阅 store（无框架）
│   │   └── narration.ts    # 叙事日志（NarrationLine[]）
│   └── net/
│       ├── api.ts          # REST 调用封装（含超时/重试）
│       └── ws.ts           # WebSocket 事件监听
└── tsconfig.json
```

### 3.2 样式组织（保持 TRPG 极简文字风）

- CSS 变量定义主题（`--bg/--text/--accent`），支持夜间模式切换（对应旧设置面板）。
- **叙事区**：垂直滚动日志，行按 `NarrationKind` 着色（Input/Narration/System/Dialogue/Combat/Reward/Error 7 类）。
- **选项卡片**：叙事区底部横向卡片（方向/附近/遭遇/对话），大字号、边框描边、无图片。
- **覆盖层**：全屏半透明遮罩 + 居中面板，点击遮罩关闭（对应 `OverlayPanel` 契约）。
- **轻薄链接**：顶栏文字链接（角色/行囊/技能/制造/委托/家园/地图/图鉴/商店/锻造/设置），无边框。
- 响应式：竖屏（手机）与桌面均适配，卡片换行。

### 3.3 TypeScript 构建方式

- **原生 ES Module + tsc**（无 bundler）：`tsconfig.json` 设 `"module": "es2022", "moduleResolution": "bundler"`（浏览器直跑时改 `"module": "esnext"`），`tsc --watch` 开发，产物 `dist/` 由后端 `canglan-api` 静态托管。
- 模块划分 = 目录划分（§3.1）；每个文件一个职责；`import` 用相对路径（浏览器原生 ESM 需带 `.js` 后缀，构建时保留）。
- 若需体积优化，后期可加 esbuild 打包，但**一期禁止引入框架**。

### 3.4 前端状态管理

- **页面状态机**：`type PageState = 'start'|'saveSelect'|'creation'|'game'`，`store.setPage()` 切换 DOM 区块显隐（4 个 `<section>` 互斥）。
- **叙事日志**：`NarrationLine { text, kind }[]`，store 订阅后增量 append DOM（虚拟滚动可选，一期直接 append + scrollTop）。
- **动态选项**：后端每次响应返回 `actions: string[]`（随坐标变化），前端渲染为卡片；静态注册表内容（装备/建筑）由覆盖层面板按需拉取。
- **覆盖层状态**：`overlayPanel: 'Char'|'Bag'|...|null`，null = 关闭。
- **无全局状态库**：一个 50 行的发布订阅 `store`（subscribe/emit）足够，禁止引入 Redux/MobX。

### 3.5 前端调用后端

**通信取舍**：
- **REST**：指令执行、面板数据拉取、存档 CRUD（请求-响应模型）。
- **WebSocket**：后端 EventBus 事件推送（战斗回合、成就解锁、遭遇触发）→ 前端实时刷新叙事区。**不用 SSE**（双向需求：遭遇选择需回传）。
- 指令输入/选项点击 → `POST /api/game/command`；面板刷新 → `GET /api/panel/{name}`；存档读档 → `POST /api/save/{slot}/load`。

**交互流程**（以「攻击 哥布林」为例）：
1. 前端点击卡片 → `POST /api/game/command {line: "攻击 哥布林"}`
2. Java CommandDispatcher 执行 → BattleManager 回合循环 → 每回合 EventBus 发射事件
3. WebSocket 推送 `TURN_START/DAMAGE_DEALT/...` → 前端追加叙事行
4. 战斗结束 → 响应返回 `{narration, actions, state}` → 前端刷新选项与 HUD

## 4. AI 模块（Python + LangGraph）

### 4.1 LangGraph 承担能力

| 能力 | LangGraph 实现 |
|---|---|
| NPC 自由对话 | 图：`召回记忆节点 → 构建 prompt 节点 → LLM 生成节点 → 安全过滤节点` |
| 二层记忆 | 记忆召回节点查询 SQLite（个体：该 NPC 亲身经历；群体：village/guild 传播）；对话后 `写入记忆节点` 按重要性 1~4 分级 |
| 任务生成 | 基于任务图当前节点 + 玩家标签 → 生成可选支线描述（仅文本，不修改图结构） |
| 传闻生成 | 基于世界状态（最近目标方向/距离）+ 标签 → 生成播报文本 |
| 行为决策建议 | 输入 NPC 标签+记忆 → 输出 `weightDelta(-30~30)` JSON（对应 AiBehaviorAdvisor 契约） |

**LLM 后端**：本地 GGUF（llama.cpp server / ollama）或远程 API；模型路径仍遵守纯 ASCII 约定。

### 4.2 通信方式（推荐：HTTP + JSON）

| 方案 | 评估 |
|---|---|
| **HTTP REST（推荐）** | 简单、易调试、天然超时控制；AI 调用低频（对话/决策），无需长连接 |
| gRPC | 过度设计；JSON 足够 |
| 本地进程调用 | 耦合重、无法独立扩缩 |
| 消息队列 | 对话需同步响应，MQ 不适合 |

Java 端用 `HttpClient`（JDK 11+），**超时 3s**；Python 端 FastAPI + uvicorn，`/health` 探活。

### 4.3 AI 降级保障（铁律）

```java
public interface AiClient {
    boolean isAvailable();
    CompletableFuture<ChatReply> chat(ChatRequest req);
    CompletableFuture<List<Integer>> behaviorWeights(WeightRequest req);
}
```
- `LangGraphHttpClient` 实现 + `AiCircuitBreaker`：连续 3 次失败/超时 → 熔断 30s → 期间直接返回 `RuleFallbackService` 结果。
- `RuleFallbackService`：静态对话树 / 关键词匹配 / 固定文案 / weightDelta=0。
- **任何 AI 调用失败不得抛异常到游戏主流程**；CommandDispatcher 捕获后走规则路径。
- 启动时 AI 不可用 → `NullAiClient`（等价旧 NullAiService），游戏照常启动。

## 5. 数据层（JSON + SQLite 混合）

### 5.1 职责划分

| 数据类型 | 存储 | 理由 |
|---|---|---|
| tags/races/classes/quests/items/equipments/skills/monsters/npcs/recipes/buildings/shops/achievements 等 21 个配置 | **JSON**（data/ 目录，只读） | 静态、版本化、设计期可编辑 |
| 存档（10 槽位） | **SQLite** `saves` 表 | 需事务、原子写 |
| AI 二层记忆 | **SQLite** `memories` 表 | 需索引查询、衰减裁剪 |
| 生涯统计/成就/图鉴进度 | **SQLite** `stats`/`codex` 表 | 随存档持久化 |
| 运行时会话（未存档） | **内存** | GameSession，不落盘 |

### 5.2 关键表结构

```sql
-- 存档：value = SaveData 的 JSON（仅源头数据！）
CREATE TABLE saves (
  slot        INTEGER PRIMARY KEY CHECK (slot BETWEEN 0 AND 9),
  version     INTEGER NOT NULL,
  timestamp   INTEGER NOT NULL,          -- Unix ms
  play_time   INTEGER NOT NULL,
  location    TEXT,
  level       INTEGER,
  data        TEXT NOT NULL               -- 源头数据 JSON（不含衍生状态）
);

-- AI 二层记忆
CREATE TABLE memories (
  id          TEXT PRIMARY KEY,
  scope       TEXT NOT NULL,              -- 'individual'|'group'
  owner_id    TEXT NOT NULL,              -- NPC id 或 village/guild id
  event_type  TEXT, summary TEXT, tags TEXT,   -- JSON 数组
  importance  INTEGER CHECK (importance BETWEEN 1 AND 4),
  turn        INTEGER, affinity_delta INTEGER,
  decay_turns INTEGER DEFAULT 30          -- -1 = 永久
);
CREATE INDEX idx_mem_owner ON memories(scope, owner_id);

-- 生涯统计 / 图鉴 / 成就（随存档绑定）
CREATE TABLE stats  (save_slot INTEGER, key TEXT, value INTEGER, PRIMARY KEY(save_slot, key));
CREATE TABLE codex  (save_slot INTEGER, entry_type TEXT, entry_id TEXT, progress INTEGER, PRIMARY KEY(save_slot, entry_type, entry_id));
```

### 5.3 存档哲学保持

- `saves.data` 中**只序列化源头数据**（玩家名/种族 id/职业 id/标签 id 集合/等级/金币/生存值/背包/装备 id/迷雾行/家园/AI 记忆引用），**不含** ActiveTags、派生属性、Buff 计算结果。
- 读档：`GameLoader.load` → `RestoreIdentity` → `recalculateTags()` 全量重建（与 C# 完全一致）。
- Java 端 SaveData 用 Jackson 序列化为 JSON 存入 TEXT 列；版本迁移逻辑（`Migrate`）保留。

## 6. 迁移步骤（依赖顺序）

| 阶段 | 迁移内容 | 前置依赖 | 验收 | 状态 |
|---|---|---|---|---|
| **P1 基础层** | EventBus + Tag 系统 + Effect DSL + JSON 加载框架 | 无 | 标签解析/条件求值单测通过 | ✅ |
| **P2 数据注册表** | Item/Buff/Equip/Skill/Graph 三图 Registry | P1 | 21 个 JSON 全部加载 + 引用完整性校验 | ✅ |
| **P3 Unit 与存档** | Unit 统一模型 + SaveManager + GameLoader + SQLite 存储 | P1, P2 | 存档→读档→重建属性一致 | ✅ |
| **P4 世界** | WorldMap/FogOfWar/Survival/PopulateWorld | P2, P3 | 固定种子布局一致、迷雾更新正确 | ✅ |
| **P5 战斗** | BattleManager + GridSystem + BattleAI | P3 | 三种战斗模式回合正确 | ✅ |
| **P6 API 层** | CommandDispatcher + REST/WS + GameSession | P3, P4, P5 | 32 个指令可执行 | ✅（WS 降级为响应驱动，见 ACCEPTANCE §3） |
| **P7 AI 集成** | Python LangGraph 服务 + AiClient + 降级 | P6 | AI 不可用时规则回退正常 | ✅（降级路径验证；Python 服务未本机部署） |
| **P8 前端** | HTML/CSS/TS 四页面 + 覆盖层 + 叙事引擎 | P6 | 页面流程与旧版一致 | ✅（UI 与原 Avalonia 一比一对齐） |
| **P9 验收** | 全链路冒烟 + 契约比对 | 全部 | 见 §9 验收标准 | ✅（见 ACCEPTANCE.md） |

**铁律**：P1（标签）→ P2（注册表）→ P3（Unit）顺序不可颠倒；P7/P8 可并行。

## 7. 关键接口定义

### 7a. Java 后端对外接口（REST + WS）

```
POST /api/game/new                 # 新游戏（返回 sessionId）
POST /api/game/command             # {sessionId, line} → {narration[], actions[], hud}
GET  /api/game/actions             # 当前动态选项（随坐标）
GET  /api/panel/{name}             # 覆盖层面板数据（char/bag/skill/...）
POST /api/save/{slot}              # 手动存档
POST /api/load/{slot}              # 读档
GET  /api/save/slots               # 槽位列表
WS   /ws/game/{sessionId}          # 事件推送（EventBus 桥接）
GET  /api/health                   # 探活（含 AI 可用性）
```

### 7b. 前端调用后端（= 7a 的消费方）

前端 `net/api.ts` 封装：`sendCommand(line)`、`openPanel(name)`、`save(slot)`、`load(slot)`；`net/ws.ts` 监听事件后更新 store。**前端不解析指令、不持有游戏逻辑**——仅渲染与转发。

### 7c. Python AI 服务接口（FastAPI）

```
GET  /health                       # 探活
POST /chat                         # NPC 自由对话
     req:  {npc_id, player_name, tags[], memories[], message}
     resp: {reply, memory_entries[], affinity_delta}
POST /behavior-weights             # 行为决策建议
     req:  {npc_id, tags[], memories[], options[]}
     resp: {weight_deltas: [-30..30, ...]}
POST /generate-quest               # 任务文本生成（不修改图）
POST /generate-rumor               # 传闻生成
     req:  {world_state, player_pos}  resp: {text}
```

### 7d. Java 调用 Python（AiClient 内部）

`LangGraphHttpClient` 调 7c 接口，超时 3s；失败 → CircuitBreaker → `RuleFallbackService`。请求/响应均为 Jackson POJO。

## 8. 核心契约清单（必须保留）

| # | 契约 | 来源 |
|---|---|---|
| 1 | 标签驱动：条件/效果 DSL，分类与来源枚举 | PROJECT_CORE §4.1 |
| 2 | Unit 统一模型：role + 行为池 + 关系状态区分玩家/NPC/怪/队友 | §4.2 |
| 3 | EventBus：同步发射、owner 批量清理、payload 智能映射 | §4.3 |
| 4 | 存档哲学：只存源头数据，读档 recalculateTags 全量重建 | §4.4 |
| 5 | 地图/迷雾/层级：圆形视野、Visible→Explored、多层 MapLayer | §4.8 |
| 6 | 任务图系统：GraphEngine 三图，标签条件驱动转职/进化 | §3, §4.1 |
| 7 | 空间化行动：NearbyRange=4 / NpcRange=4，选项随坐标刷新 | §5 |
| 8 | AI 降级策略：永不阻塞，失败回退规则，Null 实现兜底 | §4.5, §8.3 |
| 9 | Bootstrap 加载顺序：ItemRegistry 先于 Unit | §3 |
| 10 | 二层记忆：个体≤60 条、重要性衰减、与 Unit 标签分离 | §4.6 |

## 9. 风险点与对策

| 风险 | 对策 |
|---|---|
| **a. C#→Java 差异**：record/属性→Java record/getter；async/await→CompletableFuture；HashSet/Dictionary→HashSet/HashMap；System.Text.Json→Jackson（日期/枚举需配置） | 建立映射表逐项转换；EventBus 保持同步避免异步复杂度；枚举用 String 序列化对齐 JSON |
| **b. Avalonia MVVM→Web DOM**：数据绑定→手动 DOM 更新；ObservableCollection→store 订阅；Command→事件监听 | 前端 store 极简发布订阅；叙事区增量 append；覆盖层用 CSS class 切换 |
| **c. LLamaSharp→LangGraph**：进程内推理→外部服务；延迟增大；模型加载差异 | 3s 超时 + 熔断 + 规则回退；AI 调用全部异步；保留 NullAiClient 启动路径 |
| **d. JSON 配置规模扩大**：加载慢、引用错误难排查 | 启动时 fail-fast 校验 + 引用完整性测试；按模块懒加载（图鉴等）；JSON Schema 校验 |
| **e. SQLite 并发/事务/存档一致性** | 单写多读（WAL 模式）；存档写入单事务；AI 记忆批量写单事务；避免跨表长事务；定期 VACUUM |

## 10. 清理与验收策略

### 10.1 清理策略

- **迁移期间**：旧 C#/.NET/Avalonia 文件（src/、tests/、data/、publish/）**全部保留**，新旧并行，随时可回退对照。
- **清理时机**：仅当 §10.2 验收全部通过、且新架构稳定运行 ≥1 个迭代后，再制定清理计划。
- **清理范围**（届时）：删除 src/GameApp.Android、publish/、obj/bin 产物、.inscode/.atomcode 临时文件；data/ 保留（新架构继续使用）；旧 csproj/sln 归档到 archive/。
- **实际执行（已完成）**：验收通过后按用户指示直接删除旧栈全部文件（src/、tests/、publish/、Test.sln、Directory.Build.props、.idea/.inscode/.atomcode/.vscode 及根目录临时产物，未归档），data/ 保留。

### 10.2 验收标准

1. **核心玩法可运行**：创建角色（种族→职业→特质）→ 移动/探索 → 战斗（三种模式）→ 采集/制造 → 任务完成 → 存档/读档，全流程无异常。
2. **标签/任务行为一致**：标签条件触发转职/进化的结果与旧版单测（93 项）断言一致。
3. **存档一致**：存档 JSON 仅含源头数据；读档后 recalculateTags 重建的属性/标签与存档前一致。
4. **AI 降级一致**：Python 服务停止时，对话/决策/传闻全部回退规则，游戏不阻塞、不报错。
5. **空间化一致**：村庄内无怪无采集点；NearbyRange/NpcRange 半径行为与旧版一致。
6. **前端交互一致**：页面状态机四步流程、11 覆盖层面板、选项卡片随坐标刷新均正常。

# 苍岚大陆 · Agent 核心速览

> 给 agent 的浓缩参考：先读本文再动手。细节以代码为准，本文描述的是稳定架构契约与约定。

## 1. 项目定位

**标签驱动的 TRPG 文字冒险游戏**（C# / .NET 8 + Avalonia UI 11，桌面 + 安卓双端）。
核心设计哲学：

- **标签驱动**：种族进化、职业转职、任务、NPC 关系、制造条件全部由标签（tag）条件触发，不是硬编码逻辑。
- **图驱动成长**：种族进化图 / 职业转职图 / 任务图三个 GraphEngine（数据在 `data/races.json`、`classes.json`、`quests.json`）。
- **全行动空间化**：行动按钮随玩家坐标动态变化，村庄内无怪无采集点；攻击/采集/交谈/制造都有位置门槛。
- **本地 AI**：LLamaSharp 直连 GGUF 模型做自由对话文本生成；**AI 永不阻塞、失败自动降级为规则逻辑**（铁律）。
- **开拓者式交互**：移除输入框与底栏指令，改用叙事区底部大卡片选项（方向/附近/遭遇/对话）+ 轻薄文字链接打开覆盖层面板。

## 2. 工程结构

```
Test.sln
├── src/
│   ├── GameCore/          # 游戏核心，零 UI 依赖（net8.0;net8.0-android）
│   ├── GameApp/           # Avalonia 客户端 MVVM（android 目标编译为 Library，入口由 GameApp.Android 提供）
│   ├── GameApp.Android/   # 安卓入口（Activity + Assets 打包，不在 sln 内）
│   └── GameDemo/          # 控制台演示
├── tests/
│   ├── GameCore.Tests/    # 核心测试（自研测试框架，Program.cs 断言，93 项）
│   └── UiSmoke/           # UI 冒烟（页面流程 + 空间化断言）
├── data/                  # 21 个 JSON 配置（csproj 自动拷贝到输出 Data/）
└── publish/win-x64/       # 发布产物
```

GameCore 模块目录：`Tag`（标签契约）、`Graph`（三图）、`Unit`（统一单位）、`Battle`、`World`（地图/生存/时间/遭遇）、`AI`（本地推理+二层记忆）、`Save`、`EventBus`、`Effect`、`Buff`、`Item`、`Equipment`、`Skill`、`Craft`、`Home`、`Npc`、`Monster`、`Social`（声望/阵营）、`Ally`（队友）、`Stats`、`Achievements`、`Bestiary`（图鉴）、`Shop`、`Creation`、`Behavior`。

## 3. 装配入口：GameWorld.Bootstrap

[GameWorld.cs](src/GameCore/GameWorld.cs) 是唯一引导入口：`GameWorld.Bootstrap(dataDir, width, height, saveDir, rng, aiSettings)`。**加载顺序是铁律，不可调换**：

1. `AiContext.Create()` — 离线自动装配 Null 实现，不阻塞启动
2. **tags.json → ItemRegistry（items.json）必须先于任何 Unit 创建**（Inventory 构造捕获 Instance）
3. buffs / equipments / setBonuses
4. skills / skillTrees
5. 三图：races → classes → quests
6. monsters / resources / recipes / buildings / npcs / traits / tag_affinity / bond_skills
7. achievements / shops
8. WorldMap + SurvivalManager + PopulateWorld（固定种子 20260804 撒 NPC/怪/采集点）
9. SaveManager + GameLoader
10. 工厂：CharacterCreation / MonsterFactory / NPCFactory
11. 长线系统：Factions / Codex / Stats / Achievements

新增 JSON 注册表时：在 Bootstrap 中按依赖顺序注册，并在 csproj `data/**/*.json` 已覆盖无需改动。

## 4. 核心系统契约

### 4.1 标签系统（一切的基础）
- [TagRegistry](src/GameCore/Tag/TagRegistry.cs)：`tags.json` → TagDef（id/name/category/tier/allowedSources/effects/behaviorWeights）。
- 分类：Element / Identity / Personality / Emotion / QuestMark / Skill；来源：Race / Class / Quest / Trait / Equip / Buff。
- Unit 持有 `QuestTagIds`（不可逆）/ `TraitTagIds` / `EquipTagIds`（穿戴注入，卸下移除）→ `RecalculateTags()` **无状态全量重建** ActiveTags。
- 条件 DSL：`TagCondition` + `TagConditionParser`（如 `has:xxx`、`tier:xxx>=2`）；效果 DSL：`EffectParser` → `EffectEngine`（标签层级/词缀）。
- 新增标签类型必须：数据加进 tags.json → TagRegistry 解析 → 需要时挂接条件/效果。

### 4.2 Unit 统一模型
[Unit.cs](src/GameCore/Unit/Unit.cs)：玩家、NPC、怪物、队友**同构**，差异 = role + 行为池（SocialPool/CombatPool/ActivePool）+ 关系状态；转换只改 role/relationState，不新建对象。关键字段：Stats（基础属性）、Survival（饱食/水分/体温/理智）、Inventory、WorldPos、Affinity、AllyAffinities、IsMercenary/ContractDuration、Difficulty。

### 4.3 EventBus（同步解耦总线）
[EventBus.cs](src/GameCore/EventBus/EventBus.cs)：`Emit(type, params payload)` 按类型智能映射 key（Unit→target/source/unit，数字→amount，字符串→text）；`SubscribeWithOwner` 支持按 owner 批量清理（RecalculateTags 时用到）。事件类型全集见 `EventTypes`（BATTLE_START、UNIT_DEATH、QUEST_COMPLETED、PLAYER_MOVED、GAME_LOADED、AUTO_SAVE 等）。

### 4.4 存档哲学（重要）
[SaveManager.cs](src/GameCore/Save/SaveManager.cs)：**只存「输入」源头数据，不存「输出」衍生状态** → 文件极小、迁移安全；读档时由 `GameLoader` 经 `RestoreIdentity` + `RecalculateTags` 全量重建。10 槽位（0 = 自动存档），`AutoSaveTrigger` 触发点：战斗结束/任务完成/每 50 步移动/进新区域/退出。`DeathHandler` 三模式：Permadeath 删档 / Reload 强制读档 / Penalty 扣金币经验复活。新增可存档状态 → 在 `SaveData` + `Capture` + `GameLoader.Load` 三处同步。

### 4.5 本地 AI（三层降级）
[AiContext.cs](src/GameCore/AI/AiContext.cs)：`Direct`（LLamaSharp 直连 GGUF）/ `Ollama`（HTTP）/ `Auto`（先 Direct 后 Ollama）；全部不可用 → Null 实现（不抛错）。**约束：强制 Direct 但缺模型文件时不得回退服务，直接 Null**。模型文件在 `C:\GameModels`（必须纯 ASCII 路径，llama.cpp 打不开中文路径）。
- 调用方：`world.Ai.Chat.IsAvailable` 判断后再用；对话走 `AiFreeTalkAsync`，失败回退规则对话。
- **AI 决策融合**：[AiBehaviorAdvisor](src/GameCore/AI/AiBehaviorAdvisor.cs) 基于标签与记忆输出 weightDelta(-30~30) 叠加到规则权重，AI 失效时纯规则决策。

### 4.6 二层记忆系统
[AiMemory.cs](src/GameCore/AI/AiMemory.cs)：个体记忆（每 NPC 一份，上限 60 条，重要性 1~4 决定衰减）+ 群体记忆（village/guild 传播）。记忆标签与 Unit 标签体系**分离**，不进入 ActiveTagIds（避免污染数值）。好感度变化/声望事件自动写入群体记忆；存档持久化（AiMemories）。

### 4.7 战斗
[BattleManager.cs](src/GameCore/Battle/BattleManager.cs)：双九宫格回合制（GridSystem），阶段 Init→PlayerTurn→EnemyTurn→NpcInterrupt→Resolve→BattleEnd；模式：SPAR 切磋 / ROB 打劫（不致死）/ LETHAL 袭杀；支持掩护/背刺/NPC 监听介入/连击追踪/技能冷却。玩家行动通过 `AllyActionSelector` 钩子注入（VM 端提供）。

### 4.8 世界
[WorldMap.cs](src/GameCore/World/WorldMap.cs)：50x50，多层（地表/地下/天空 MapLayer），`FogOfWar` 圆形视野（dx²+dy²≤range²），视野离开后 Visible→Explored，存档按行导出 U/E/V 字符。`TerrainFeature` 承载 NPC/怪物刷新点/采集点/设施（布告板、水井）。`Survival`：生态区影响消耗，饥饿/口渴/体温临界事件。`RandomEncounter` 遭遇按区域概率，含低概率世界 Boss「荒原领主」。

## 5. UI 架构（GameApp）

- 页面状态机：`PageState { Start, SaveSelect, Creation, Game }`。
- [MainViewModel.cs](src/GameApp/ViewModels/MainViewModel.cs)（1600 行，partial，分 Hud/Systems）：文字叙事引擎 + 指令解析器 `ExecuteCommandAsync`。**叙事铁律：所有游戏逻辑走规则系统；AI 仅用于自由对话文本，失败回退，永不阻塞**。
- 指令集（中文指令解析）：`创建/状态/背包/标签/装备/东/南/西/北/前往/查看/环顾/探索/等待/交谈/攻击/采集/吃/喝水/制造/配方/任务/传闻/完成/技能/解锁技能/建造/家园/家园升级/招募/交易/存档/读档/存档列表/声望`；**未识别指令 → AI 自由对话**。全角空格归一化处理。
- 覆盖层：`OverlayViewModel` 状态机，11 种面板（Char/Bag/Skill/Craft/Quest/Home/Map/Codex/Shop/Forge/Settings），点击遮罩关闭。
- 视图：`Views/MainView.axaml`（游戏主界面）+ `MainWindow.axaml`（窗口壳）+ `App.axaml`。
- 空间化：`NearbyRange=4`（怪/采集可行动半径）、`NpcRange=4`（交谈半径）；选项集合随玩家坐标刷新（`FillDynamicOptions`），静态注册表内容（装备/建筑）单独填充。

## 6. 数据文件（data/*.json）

| 文件 | 用途 |
|---|---|
| tags.json | 标签契约（条件/效果/行为权重） |
| races.json / classes.json / quests.json | 三图（进化/转职/任务） |
| items.json / equipments.json / setBonuses.json / buffs.json | 物品与装备体系 |
| skills.json / skillTrees.json | 技能与技能树 |
| monsters.json / npcs.json / traits.json | 单位模板 |
| resources.json / recipes.json / buildings.json / bond_skills.json | 采集/制造/家园/羁绊技能 |
| tag_affinity.json | 标签亲密度矩阵 |
| shops.json / achievements.json | 商店与成就 |
| 其余 | 发行版同样拷贝（publish/win-x64/Data/） |

## 7. 构建 / 测试 / 发布

```bash
# 桌面运行
dotnet run --project src/GameApp
# 核心测试（93 项）与 UI 冒烟
dotnet run --project tests/GameCore.Tests
dotnet run --project tests/UiSmoke
# Windows 自包含发布（多目标项目必须 -f）
dotnet publish src/GameApp/GameApp.csproj -c Release -r win-x64 --self-contained true -o publish/win-x64
# 安卓 APK（必须从纯 ASCII Junction 路径构建，见陷阱）
dotnet build C:\canglan-trpg\src\GameApp.Android -c Debug -f net8.0-android
# 产物: src/GameApp.Android/bin/Debug/net8.0-android/com.canglan.trpg-Signed.apk
```

## 8. 关键约定与陷阱（务必遵守）

1. **路径必须纯 ASCII**：aapt2 无法处理含 `#` 或中文的路径 → 安卓构建需先建 Junction（`New-Item -ItemType Junction C:\canglan-trpg -Target <仓库>`）；llama.cpp 也无法打开非 ASCII 路径的 GGUF → 模型固定放 `C:\GameModels`。
2. **多目标项目发布必须带 `-f`**（net8.0;net8.0-android），否则报错。
3. **AI 降级保障**：任何 AI 功能必须有规则回退路径，AI 故障不得中断核心玩法。
4. **UI 线程安全**：async void 中关键逻辑必须 try/catch；UI 元素订阅（CollectionChanged 等）应在 DataContextChanged / AttachedToVisualTree 中进行；耗时操作（AI 探测）移出 UI 线程。
5. **存档只存源头数据**，衍生状态一律读档时重建。
6. **ItemRegistry 必须先于 Unit 创建**（Bootstrap 顺序铁律）。
7. **数据 JSON 修改后**：csproj 用 `CopyToOutputDirectory="PreserveNewest"`，重新构建即生效；发行版需重新 publish 并同步 `publish/win-x64/Data/`。
8. Android SDK：`Directory.Build.props` 自动从 `%LOCALAPPDATA%\Android\Sdk` 定位，命令行构建必需。

## 9. 常见改动定位索引

| 需求 | 改哪里 |
|---|---|
| 新标签/新效果/新条件 | data/tags.json + Tag/Effect 目录 + TagConditionParser/EffectParser |
| 新种族/职业/任务 | data/races|classes|quests.json（Graph 数据） |
| 新物品/装备/技能 | data/items|equipments|skills.json + 对应 Registry |
| 新指令/玩法 | MainViewModel.ExecuteCommandAsync + 对应 Cmd* 方法 + 动态选项刷新 |
| 新 UI 面板 | OverlayViewModel + MainView.axaml + MainViewModel 面板刷新方法 |
| 存档字段变更 | SaveData + SaveManager.Capture + GameLoader.Load 三处 |
| 新事件 | EventBus.EventTypes + Emit/Subscribe |
| 新 JSON 配置 | data/ + 对应 Registry.Load + GameWorld.Bootstrap 注册 |

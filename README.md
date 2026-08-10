# 苍岚大陆 —— 标签驱动的纯文字 TRPG 冒险

一款 **Java 17 零依赖后端 + 纯 HTML/CSS/TypeScript Web 前端** 的文字冒险游戏（TRPG），界面完全文字化：无图标、无符号、无彩色条块。世界由「标签」驱动：种族进化、职业转职、任务推进、NPC 关系、采集制造全部由标签条件触发；NPC 自由对话由内嵌 AI 管线（二层记忆 + prompt + 可选 LLM 端点 + 规则兜底）驱动，永不阻塞游戏。

> 本项目由 C#/.NET/Avalonia 桌面版迁移而来（迁移过程与验收见 `MIGRATION_PLAN.md` 与 `ACCEPTANCE.md`），旧栈文件已按约定清理，`data/` 配置双栈共用。

## 特性

- **四步页面流程**：开始界面 → 存档选择（3 存档位）→ 角色创建（血脉/道路/特质/难度点选）→ 游戏
- **纯文字界面**：状态、叙事、行动、地图全部文字表达，选中态用加粗下划线；允许少量语义色彩（战斗红/奖励金/交互蓝）
- **移动端适配**：窄屏下按钮加大至 44px、覆盖层面板全屏、支持触屏按压反馈与减动效偏好
- **全行动空间化**：行动按钮随玩家坐标动态变化——村庄里没有怪物与采集点，走出村庄才会遇到；攻击/采集/交谈/招募/布告板/家园/建造/制造/喝水都有位置门槛
- **传闻引导**：不知道去哪时点「传闻」，播报最近目标的方向与距离
- **图驱动成长**：12 种族进化图、8 职业转职图、30 任务图，创建时只列根种族（5）与基础职业（4）
- **战争迷雾**：多层地图（地表/地下/天空），圆形视野随移动更新
- **文字地图**：50x50 地图一格一场景，地形为名称首字（平/林/沙/苔/沼/山），要素为单字（怪/人/采/建/门/家），玩家为「你」，未探索留白
- **生存系统**：饱食/水分/体温/理智，生态区影响消耗
- **八大功能系统**：探索 / 行囊 / 技能 / 制造 / 委托 / 家园 / 图鉴 / 设置
- **图鉴**：怪物 / 物品 / 配方三类百科 + 关键词过滤
- **成就系统**：18 项生涯成就（击杀/探索/制造/委托/社交/生存），解锁发放金币、物品与标签奖励
- **商店系统**：商人 NPC 开店，买入扣库存、卖出半价回收、锁定物品不可卖、货架每日补货
- **锻造系统**：家园铁匠铺 修理（金币恢复耐久）/ 强化（白→绿→蓝→紫→金，消耗矿石）/ 附魔（追加随机词缀，上限 4 条），自动重穿刷新属性
- **世界 Boss**：低概率遭遇「荒原领主」等稀有事件，高风险高回报
- **生涯记录**：步数/战斗/击杀/采集/制造/金币全程统计，随存档持久化
- **本地 AI**：内嵌二层记忆管线（个体+群体记忆、prompt 构建、安全过滤、记忆回写），可选接 OpenAI 兼容 LLM 端点；也可外接 Python LangGraph 服务，全部失败路径规则兜底
- **存档体系**：3 存档位，建档自动存档 + 手动存档，恢复家园/迷雾/好感/声望/生涯统计/成就

## 目录结构

```
├── canglan-backend/    # Java 17 零依赖后端（core/data/world/save/ai-client/api 六模块 + android-app 安卓壳）
├── frontend/           # Web 前端（src 为 TS 源码，dist 为产物+静态资源）
├── ai-service/         # Python LangGraph AI 服务（可选部署）
├── data/               # 全部 JSON 配置（种族/职业/任务/怪物/NPC/物品…）
├── build-all/          # javac 编译产物（classpath 根）
├── build-android.ps1   # Android APK 纯命令行构建（javac+d8+aapt2+apksigner，需 Android SDK）
├── build-pc.ps1        # PC 独立发行包构建（jar+jlink 精简 JRE）
├── run-regress.js      # 七套回归一键运行器（node run-regress.js）
├── PROJECT_CORE.md     # 项目核心文档（给 agent 的系统速读）
├── MIGRATION_PLAN.md   # 跨技术栈迁移方案（P1~P9 全部完成）
└── ACCEPTANCE.md       # P9 验收报告
```

## 构建与运行（零外部依赖：仅 JDK 17 + Node）

```powershell
# 1. 编译 Java 后端（无 Maven，javac 直编）
Get-ChildItem -Recurse -Filter *.java -Path canglan-backend\canglan-core\src,canglan-backend\canglan-data\src,canglan-backend\canglan-world\src,canglan-backend\canglan-save\src,canglan-backend\canglan-ai-client\src,canglan-backend\canglan-api\src |
  ForEach-Object { $_.FullName } | Set-Content sources.txt -Encoding utf8
javac --release 17 -encoding UTF-8 -d build-all "@sources.txt"

# 2. 编译前端 TS（仅 typescript 一个 devDependency，dist 中已含 index.html/css）
node_modules\.bin\tsc.cmd -p frontend\tsconfig.json

# 3. 启动服务（默认内嵌 AI 管线；可选接外部 AI 服务或 LLM 端点，见「AI 模块」节）
java "-Dfile.encoding=UTF-8" -cp build-all com.canglan.api.HttpApiServer data saves-frontend 8792 frontend\dist
# 浏览器打开 http://localhost:8792/

# 4. 回归测试（七套共 243 断言，一键运行或单套执行，详见 ACCEPTANCE.md）
node run-regress.js
java -cp build-all com.canglan.data.BootstrapSmokeTest data        # 14
java -cp build-all com.canglan.world.WorldSmokeTest data            # 33
java -cp build-all com.canglan.world.battle.BattleSmokeTest data    # 71
java -cp build-all com.canglan.save.SaveRoundTripSmokeTest data build-save-test   # 26
java -cp build-all com.canglan.api.ApiSmokeTest data build-api-test               # 64
java -cp build-all com.canglan.ai.AiSmokeTest data                                # 22
java -cp build-all com.canglan.api.FullFlowSmokeTest data build-flow-test         # 13
```

## 发行版构建

```powershell
# PC 独立版（Windows x64，内置精简 JRE，双击启动.bat 即玩）
.\build-pc.ps1          # 产物 dist/canglan-trpg-win-x64.zip（约 19 MB）

# Android 单机 APK（内置后端，WebView 前端，需本机 Android SDK；minSdk 30 / Android 11+）
.\build-android.ps1     # 产物 dist/canglan-trpg-android.apk
```

说明：
- **传输层**：后端 HTTP 服务为自研 `MiniHttpServer`（ServerSocket 实现，`canglan-api` 模块），PC 与 Android 共用同一套代码；JDK 内置的 `com.sun.net.httpserver` 在 Android 上不存在，故不使用。
- **Android 形态**：APK 启动时将 assets 中的 data/web 解压到应用内部存储，后台线程起本地 HTTP 服务（仅回环监听，随机端口），WebView 加载 127.0.0.1；存档存应用沙箱，卸载即清。debug 签名仅供侧载安装。
- **构建路径**：Android 构建经 `C:\canglan-trpg` 目录联接（ASCII 路径）执行，规避 aapt2 不支持中文/# 路径的问题；无 Gradle/Maven，纯 javac + d8 + aapt2 + zipalign + apksigner。

## AI 模块（内嵌管线 + 供应商接入，零依赖）

NPC 自由对话由 `canglan-ai-client` 内嵌管线驱动（Python `ai-service/main.py` 的 Java 移植，Android 兼容）：召回记忆 → 构建 prompt → LLM 生成（可选）→ 安全过滤 → 写入记忆；记忆以 `saveDir/memories.json` 持久化（个体 npcId + 群体 group:village/guild，上限 500 条）。接入优先级：

1. **外部 AI 服务**（可选）：`-Dcanglan.ai.url=http://localhost:8000` 指向 `ai-service/main.py`（Python LangGraph 版），探活失败自动降级内嵌管线；`-Dcanglan.ai.url=off` 显式完全禁用；
2. **LLM 供应商接入**（设置页配置）：起始页「AI 设置」（游戏内设置面板亦可）选择供应商——本地模型服务（Ollama/llama.cpp，免密钥）或云端模型（DeepSeek/OpenAI/Kimi/GLM/通义千问等，需 API 密钥），统一为 OpenAI 兼容端点（地址/密钥/模型名）；支持「测试连接」试连，保存立即生效并持久化于 `saveDir/ai-config.json`（后端 `/api/ai/config`、`/api/ai/test` 端点）；失败自动降级规则回复（含熔断）；
3. **默认形态**：无外部服务、未配置供应商时，内嵌规则引擎 + 记忆召回兜底。

铁律：任何 AI 调用失败不得阻塞游戏主流程（AiSmokeTest 22 断言 + ApiSmokeTest 配置端点 5 断言覆盖全部降级路径）。

## 玩法速览

- 移动用「北/东/南/西」方向按钮（标注目标地形）；「环顾四周」查看附近；「传闻」探听最近目标方向
- 村里可交谈、招募、看布告板接委托、在水井喝水；怪物与采集点都在村外
- 采集点有储量与冷却；制造/建造需回到家园
- 图鉴（底栏「图鉴」）：三类百科，关键词过滤；达成成就会有播报与奖励
- 商店（靠近商人）：买入/卖出，锁定物品不可卖
- 锻造（制造面板）：选中已穿装备，修理 / 强化品质 / 附魔
- 设置（底栏「设置」）：AI 状态、难度、保存游戏
- 走远后可能遭遇随机事件（含低概率的世界 Boss「荒原领主」），谨慎选择

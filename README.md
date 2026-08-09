# 苍岚大陆 —— 标签驱动的纯文字 TRPG 冒险

一款 **Java 17 零依赖后端 + 纯 HTML/CSS/TypeScript Web 前端** 的文字冒险游戏（TRPG），界面完全文字化：无图标、无符号、无彩色条块。世界由「标签」驱动：种族进化、职业转职、任务推进、NPC 关系、采集制造全部由标签条件触发；Python LangGraph AI 服务（可选）为 NPC 对话提供自由文本生成，未部署时自动降级为规则兜底叙事，永不阻塞游戏。

> 本项目由 C#/.NET/Avalonia 桌面版迁移而来（迁移过程与验收见 `MIGRATION_PLAN.md` 与 `ACCEPTANCE.md`），旧栈文件已按约定清理，`data/` 配置双栈共用。

## 特性

- **四步页面流程**：开始界面 → 存档选择（3 存档位）→ 角色创建（血脉/道路/特质/难度点选）→ 游戏
- **纯文字界面**：状态、叙事、行动、地图全部文字表达，选中态用加粗下划线
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
- **本地 AI**：Python LangGraph 服务（localhost:8000），未启动时自动回退规则对话
- **存档体系**：3 存档位，建档自动存档 + 手动存档，恢复家园/迷雾/好感/声望/生涯统计/成就

## 目录结构

```
├── canglan-backend/    # Java 17 零依赖后端（core/data/world/save/ai-client/api 六模块）
├── frontend/           # Web 前端（src 为 TS 源码，dist 为产物+静态资源）
├── ai-service/         # Python LangGraph AI 服务（可选部署）
├── data/               # 全部 JSON 配置（种族/职业/任务/怪物/NPC/物品…）
├── build-all/          # javac 编译产物（classpath 根）
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

# 3. 启动服务（AI 服务未部署时以空 url 走规则降级）
java "-Dfile.encoding=UTF-8" "-Dcanglan.ai.url=" -cp build-all com.canglan.api.HttpApiServer data saves-frontend 8792 frontend\dist
# 浏览器打开 http://localhost:8792/

# 4. 回归测试（七套共 228 断言，详见 ACCEPTANCE.md）
java "-Dcanglan.ai.url=" -cp build-all com.canglan.data.BootstrapSmokeTest data        # 14
java "-Dcanglan.ai.url=" -cp build-all com.canglan.world.WorldSmokeTest data            # 33
java "-Dcanglan.ai.url=" -cp build-all com.canglan.world.battle.BattleSmokeTest data    # 71
java "-Dcanglan.ai.url=" -cp build-all com.canglan.save.SaveRoundTripSmokeTest data build-save-test   # 26
java "-Dcanglan.ai.url=" -cp build-all com.canglan.api.ApiSmokeTest data build-api-test               # 59
java "-Dcanglan.ai.url=" -cp build-all com.canglan.ai.AiSmokeTest data                                # 12
java "-Dcanglan.ai.url=" -cp build-all com.canglan.api.FullFlowSmokeTest data build-flow-test         # 13
```

## 本地 AI 服务（可选）

`ai-service/main.py` 为 Python LangGraph NPC 对话服务（监听 localhost:8000）。启动后端时用 `-Dcanglan.ai.url=http://localhost:8000` 接入；未启动时后端自动降级为规则兜底对话，不影响游玩。

## 玩法速览

- 移动用「北/东/南/西」方向按钮（标注目标地形）；「环顾四周」查看附近；「传闻」探听最近目标方向
- 村里可交谈、招募、看布告板接委托、在水井喝水；怪物与采集点都在村外
- 采集点有储量与冷却；制造/建造需回到家园
- 图鉴（底栏「图鉴」）：三类百科，关键词过滤；达成成就会有播报与奖励
- 商店（靠近商人）：买入/卖出，锁定物品不可卖
- 锻造（制造面板）：选中已穿装备，修理 / 强化品质 / 附魔
- 设置（底栏「设置」）：AI 状态、难度、保存游戏
- 走远后可能遭遇随机事件（含低概率的世界 Boss「荒原领主」），谨慎选择

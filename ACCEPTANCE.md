# 苍岚大陆 · 跨技术栈迁移验收报告（P9）

> 依据 `MIGRATION_PLAN.md` §9 验收标准与 §10.2 验收清单。
> 验收时旧栈（C#/.NET/Avalonia）全部保留；验收通过后已按用户指示完成旧栈清理（见 §6）。

## 1. §10.2 六项验收结论

| # | 验收项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 核心玩法全流程无异常 | ✅ | `FullFlowSmokeTest` 13/13：建档→寻矿采集（收获矿石）→回家制造（冶炼金属锭）→遭遇战完整结算→布告板委托结算（金币+50）→存档/读档一致 |
| 2 | 标签/任务行为一致 | ✅ | 六套回归 14/33/71/26/59/12 全绿；`CommandDispatcher` pickOne 支持编号/名称/id，与 C# 三选状态机一致；任务图为标签条件驱动（HasTag 门槛） |
| 3 | 存档一致（只存源头数据） | ✅ | `SaveRoundTripSmokeTest` 26/26；`FullFlowSmokeTest` §6 读档后金币/坐标逐项一致；读档走 recalculateTags 全量重建 |
| 4 | AI 降级一致 | ✅ | `AiSmokeTest` 12/12（Python 服务不启动）；`ApiSmokeTest` 「自由对话回退」断言：未识别指令走规则兜底并提示「帮助」，永不抛异常 |
| 5 | 空间化一致 | ✅ | `WorldSmokeTest` 33/33（怪物刷新环带 5.5~22.5、NPC 半径 2.5、findNearby）；HUD `nearby` 半径 NEARBY_RANGE=4；浏览器冒烟确认村内无怪物卡 |
| 6 | 前端交互一致 | ✅ | 浏览器对照冒烟全绿（console 零报错）：四页面状态机、创建四组选卡+难度金色高亮、游戏页五区、十字移动键盘、7 格快捷栏、八 Tab 覆盖层（地图 50×50/图鉴过滤）、Tab 可直接切换 |

## 2. §7a REST 端点比对

| 计划端点 | 实现 | 说明 |
|---|---|---|
| POST /api/game/new | ✅ | 返回 sessionId |
| POST /api/game/command | ✅ | 返回 narration[]+hud |
| GET /api/game/actions | ⊘ 被合并 | 动态选项已并入 hud：`directions`（四邻地形）/`nearby`（怪物/NPC）/`quickBar`，随每条指令响应刷新，前端无需单独轮询 |
| GET /api/panel/{name} | ✅ | char/bag/skill/recipe/quest/home/codex/settings/map 九面板 |
| POST /api/save/{slot} | ✅ | 槽位 1~3，非法槽位 400 |
| POST /api/load/{slot} | ✅ | 读档重建 |
| GET /api/save/slots | ✅ | 含角色名/时间/游玩时长 |
| WS /ws/game/{sessionId} | ⊘ 降级决策 | 见 §3 |
| GET /api/health | ✅ | 含 AI 可用性 |

计划外新增（服务创建流与恢复）：
- `POST /api/game/start`：一步建档（name/race/clazz/trait/difficulty，兼容 id 与名称）
- `GET /api/creation/options`：创建页选项（race/clazz 兼容 id 与中文名称过滤 traits）
- `GET /api/game/state`：恢复全量叙事日志 + HUD（进入/刷新游戏页）

## 3. WS 事件推送降级决策

- **决策**：不实现 WebSocket，前端为**响应驱动**——每条 `/api/game/command` 响应即携带全量增量（narration + hud），覆盖层数据按需 `GET /api/panel/{name}` 拉取。
- **理由**：
  1. 零依赖约束（JDK 内置 HttpServer 无 WS 支持，自实现 RFC6455 成本高且无对应前端需求）；
  2. 原 Avalonia 客户端同为进程内同步模型，无推送语义，交互语义对齐不受影响；
  3. 游戏无后台异步事件源（战斗 runToCompletion 同步结算、时间随指令推进），不存在「指令之外的状态变更」需要推送。
- **代价**：无。若未来引入后台回合/多人事件，再以 SSE（JDK HttpServer 可分块流式输出，前端 EventSource 原生支持）补充。

## 4. §8 核心契约清单核对

| # | 契约 | 状态 |
|---|---|---|
| 1 | 标签 DSL（条件/效果、分类与来源） | ✅ TagConditionParser + EffectEngine，六套回归覆盖 |
| 2 | Unit 统一模型（role+行为池） | ✅ Unit/UnitRole，BattleSmokeTest 覆盖 |
| 3 | EventBus 同步发射/owner 清理 | ✅ EventBusImpl，WorldSmokeTest 覆盖 |
| 4 | 存档只存源头数据 + 读档重建 | ✅ SaveRoundTripSmokeTest |
| 5 | 地图/迷雾/层级 | ✅ FogOfWar Visible→Explored，WorldSmokeTest |
| 6 | 任务图 GraphEngine 三图 | ✅ 职业转职/种族进化/委托链条件一致 |
| 7 | 空间化行动 NEARBY_RANGE=4 | ✅ hud.nearby/cmdGather/cmdFight 均按半径守门 |
| 8 | AI 降级永不阻塞 | ✅ 3s 超时+熔断+RuleFallback+NullAiClient |
| 9 | Bootstrap 加载顺序 | ✅ RegistryInitializer，BootstrapSmokeTest 14/14 |
| 10 | 二层记忆（个体≤60/衰减） | ✅ AI 服务端实现（Python），AiSmokeTest 覆盖降级路径 |

## 5. 回归测试矩阵（当前基线）

| 套件 | 类 | 断言数 | 运行方式 |
|---|---|---|---|
| 数据加载 | com.canglan.data.BootstrapSmokeTest | 14 | `java -cp build-all <类> <dataDir>` |
| 世界 | com.canglan.world.WorldSmokeTest | 33 | 同上 |
| 战斗 | com.canglan.world.battle.BattleSmokeTest | 71 | 同上 |
| 存档 | com.canglan.save.SaveRoundTripSmokeTest | 26 | 同上 + saveDir |
| API | com.canglan.api.ApiSmokeTest | 59 | `-Dcanglan.ai.url=` 空值跑降级 |
| AI 降级 | com.canglan.ai.AiSmokeTest | 12 | 同上 |
| **全链路** | com.canglan.api.FullFlowSmokeTest | **13** | 同上 + saveDir |

## 6. 遗留事项与后续清理

- 验收通过后旧栈已清理：删除 src/、tests/、publish/、Test.sln、Directory.Build.props、.idea/.inscode/.atomcode/.vscode 及根目录临时产物（约 3.9 GB），data/ 保留继续供新栈使用；
- 前端 UI 验收后按用户要求重构为「极简纵向流 + 完全文字化」（去色条/去符号/去图标，地图改为文字字符格）；
- 沙箱环境无法杀旧服务，历史遗留监听端口 8787/8789/8791（均为旧代码）；
- Python LangGraph AI 服务未在本机部署（以 `-Dcanglan.ai.url=` 空值验证降级路径，符合契约 #8 设计）。

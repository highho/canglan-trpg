using GameCore;
using GameCore.AI;
using GameCore.Ally;
using GameCore.Battle;
using GameCore.Behavior;
using GameCore.Craft;
using GameCore.Equipment;
using GameCore.EventBus;
using GameCore.Graph;
using GameCore.Home;
using GameCore.Item;
using GameCore.Npc;
using GameCore.Save;
using GameCore.Skill;
using GameCore.Social;
using GameCore.Tag;
using GameCore.Unit;
using GameCore.World;

Console.OutputEncoding = System.Text.Encoding.UTF8;

void Section(string title)
{
    Console.WriteLine();
    Console.WriteLine($"==================== {title} ====================");
}

void PrintPlayer(GameCore.Unit.Unit p)
{
    Console.WriteLine($"  [{p.Name}] 种族:{p.CurrentRace?.Id} 职业:{p.CurrentClass?.Id} " +
                      $"Lv{p.Level} HP:{p.Stats.Hp}/{p.Stats.MaxHp} ATK:{p.GetStat("ATK"):F0} 金币:{p.Gold}");
    Console.WriteLine($"  标签: {string.Join("、", p.ActiveTagIds)}");
}

// ==================== 1. 世界启动（全量 JSON 配置加载） ====================
Section("1. GameWorld.Bootstrap —— 标签驱动世界启动");
var saveDir = Path.Combine(AppContext.BaseDirectory, "saves");
var world = GameWorld.Bootstrap(saveDir: saveDir, rng: new Random(42));
Console.WriteLine($"  数据目录: {world.DataDir}");
Console.WriteLine($"  种族图节点: {world.RaceGraph.AllNodes.Count}  职业图节点: {world.ClassGraph.AllNodes.Count}  " +
                  $"任务图节点: {world.QuestGraph.AllNodes.Count}");

// ==================== 2. 角色创建 ====================
Section("2. 角色创建（human/warrior/brave）");
var player = world.CharacterCreation.Create("human", "warrior", "brave", "阿凌");
PrintPlayer(player);
Console.WriteLine($"  初始背包: {string.Join(", ", player.Inventory.Stacks)}");

// ==================== 3. 世界移动 + 生存系统 ====================
Section("3. 大地图移动 / 生存 / 战争迷雾");
for (var i = 0; i < 12; i++)
{
    player.WorldPos = new MapPos(5 + i, 5);
    world.SurvivalManager.OnPlayerMove(player, world.Map);
}
Console.WriteLine($"  移动后 → 饱食度:{player.Survival.Hunger} 水分:{player.Survival.Thirst} 体温:{player.Survival.Temperature}");
Console.WriteLine($"  迷雾可见格数: {CountVisible(world.Map)}");

player.Inventory.Remove("travel_rations", 1);
player.Survival.Consume(ItemRegistry.Instance.Get("travel_rations"));
player.Survival.Drink(30);
Console.WriteLine($"  吃干粮/饮水后 → 饱食度:{player.Survival.Hunger} 水分:{player.Survival.Thirst}");

// ==================== 4. 采集与制造 ====================
Section("4. 采集 / 制造（标签解锁配方）");
var ironPoint = new GatherPoint(ResourceRegistry.Instance.Get("iron_ore"));
var got = ironPoint.Gather(player);
if (got != null) { player.Inventory.Add(got.Value.ItemId, got.Value.Count); Console.WriteLine($"  采集铁矿 +{got.Value.Count}"); }

var silverPoint = new GatherPoint(ResourceRegistry.Instance.Get("silver_ore"));
Console.WriteLine(silverPoint.Gather(player) == null
    ? "  采集银矿失败（缺少[采集]标签）—— 标签门槛生效"
    : "  采集银矿成功");

var crafting = new CraftingSystem(player, world.EventBus);
Console.WriteLine($"  当前已知配方: {string.Join("、", crafting.GetKnownRecipes().Select(r => r.Name))}");
player.Inventory.Add("dragon_meat", 1);
var meatResult = crafting.Craft(RecipeRegistry.Instance.Get("cooked_meat"), player.Inventory);
Console.WriteLine($"  烹制烤肉: {(meatResult.Success ? "成功 x2" : meatResult.Error)}");
var ingotResult = crafting.Craft(RecipeRegistry.Instance.Get("iron_ingot"), player.Inventory);
Console.WriteLine($"  冶炼铁锭: {(ingotResult.Success ? "成功" : ingotResult.Error)} —— 需[锻造]标签");

// ==================== 5. 装备系统 ====================
Section("5. 装备（属性Buff化 + 耐久）");
var equipment = world.CreateEquipment(player);
var r1 = equipment.Equip(new Equip(EquipRegistry.Instance.Get("iron_sword")));
var r2 = equipment.Equip(new Equip(EquipRegistry.Instance.Get("leather_armor")));
var r3 = equipment.Equip(new Equip(EquipRegistry.Instance.Get("lucky_ring")));
Console.WriteLine($"  铁剑:{r1.Success} 皮甲:{r2.Success} 幸运戒指:{r3.Success}");
Console.WriteLine($"  装备后 ATK:{player.GetStat("ATK"):F0} DEF:{player.GetStat("DEF"):F0}");

// ==================== 6. 技能树 ====================
Section("6. 技能树（标签解锁节点）");
var warriorTree = SkillTreeRegistry.Instance.Get("warrior_tree");
var roots = warriorTree.UnlockRoots();
Console.WriteLine($"  解锁根技能: {string.Join("、", roots.Select(s => s.Name))}");
var newSkills = warriorTree.CheckUnlocks(player.ActiveTagIds);
Console.WriteLine($"  标签解锁进阶: {string.Join("、", newSkills.Select(s => s.Name))}");
var cooldowns = new CooldownManager(player, world.EventBus);
foreach (var s in warriorTree.GetUnlockedSkills()) cooldowns.AddSkill(s);

// ==================== 7. 战斗（双九宫格 + 行为池AI） ====================
Section("7. 回合制战斗（玩家 vs 哥布林+狼）");
var goblin = world.SpawnMonster("goblin");
var wolf = world.SpawnMonster("wolf");
var grid = new GridSystem();
grid.PlaceUnit(player, new GridPosition(1, 2, Side.Ally));
grid.PlaceUnit(goblin, new GridPosition(1, 1, Side.Enemy));
grid.PlaceUnit(wolf, new GridPosition(1, 3, Side.Enemy));
var ai = new BattleAI(new BehaviorEngine(new Random(42)), new Random(42));
var battle = new BattleManager(grid, world.EventBus, ai, world.EffectEngine,
    new List<GameCore.Unit.Unit> { player }, new List<GameCore.Unit.Unit> { goblin, wolf });
battle.SkillManagers[player] = cooldowns;
var battleResult = battle.RunToCompletion();
Console.WriteLine($"  战斗结束: 玩家{(battleResult.PlayerWin ? "胜利" : "失败")} 回合数:{battle.TurnNumber}");
Console.WriteLine($"  阵亡: {string.Join("、", battleResult.Deaths.Select(d => d.Name))}");
Console.WriteLine($"  玩家 HP:{player.Stats.Hp}/{player.Stats.MaxHp}  经验:{player.Exp}");
Console.WriteLine($"  战利品背包: {string.Join(", ", player.Inventory.Stacks)}");
if (player.IsDead) player.Revive(0.5f);
player.ApplyEmotion("喜悦", 80);
player.RecalculateTags();
Console.WriteLine($"  战后情感标签: {string.Join("、", player.ActiveTagIds.Where(t => t is "喜悦" or "恐惧" or "愤怒" or "悲伤"))}");

// ==================== 8. NPC 对话树 ====================
Section("8. NPC 对话树（条件分支 + 动作）");
var receptionist = world.SpawnNpc("guild_receptionist");
var dialogue = NPCFactory.GetDialogueTree(receptionist);
var evalCtx = new EvalContext(player.ActiveTagIds, new Dictionary<string, object>(), player, receptionist);
var node = dialogue.GetRoot();
while (node != null)
{
    Console.WriteLine($"  [{receptionist.Name}] {node.Text}");
    foreach (var act in node.OnEnterActions) act.Execute(player, receptionist);
    if (node.IsExit) break;
    node = dialogue.Next(node, evalCtx);
}
Console.WriteLine($"  对话后玩家标签含[公会成员]: {player.HasTag("公会成员")}");

// ==================== 9. 任务图（链式推进 + 奖励标签） ====================
Section("9. 任务链：first_hunt → guild_registration → dragon_slay → blood_awakening");
var reputation = new ReputationSystem();
var guild = new AdventureGuild(world.QuestGraph, reputation);
var availableQuests = guild.GetAvailableQuests(player);
Console.WriteLine($"  当前可接任务: {string.Join("、", availableQuests.Select(q => q.Name))}");

CompleteQuest(world, player, "first_hunt");
CompleteQuest(world, player, "guild_registration");
Console.WriteLine($"  获得[公会成员]标签: {player.HasTag("公会成员")}");

player.Level = 5;   // 屠龙任务需要等级5
CompleteQuest(world, player, "dragon_slay");
Console.WriteLine($"  获得[屠龙者]标签: {player.HasTag("屠龙者")}  金币: {player.Gold}");
CompleteQuest(world, player, "blood_awakening");
PrintPlayer(player);

// ==================== 10. 种族进化（标签达成 → 图边迁移） ====================
Section("10. 种族进化（光明誓约 → 天使）");
CompleteQuest(world, player, "oath_of_light");
Console.WriteLine($"  获得[光明誓约]: {player.HasTag("光明誓约")}");
var angelNode = world.RaceGraph.GetNode("angel") as RaceNode;
player.ChangeRace(angelNode);
PrintPlayer(player);

// ==================== 11. 声望 / 交易 ====================
Section("11. 声望与交易折扣");
reputation.Adjust(AdventureGuild.GuildId, player.Id, 350);
var shop = new Shop(AdventureGuild.GuildId);
var trade = new TradeSystem(reputation);
Console.WriteLine($"  公会声望: {reputation.Get(AdventureGuild.GuildId, player.Id)} " +
                  $"等级: {reputation.GetRank(AdventureGuild.GuildId, player.Id)}");
Console.WriteLine($"  原价100的商品 → 实付: {trade.CalculatePrice(100, player, shop)}");

// ==================== 12. 家园系统 ====================
Section("12. 家园（建造 / 升级）");
var home = new HomeBase(new MapPos(10, 10), 8, 8, world.EventBus, player);
if (BuildingRegistry.Instance.TryCreate("farm", out var farm))
{
    var placed = home.PlaceBuilding(farm, 1, 1);
    Console.WriteLine($"  建造农场: {placed}");
}
home.LevelUp();
Console.WriteLine($"  家园等级: {home.Level}  标签[家园Lv1]: {player.HasTag("家园Lv1")} [家园Lv2]: {player.HasTag("家园Lv2")}");

// ==================== 13. 队友（招募 / 羁绊 / 成长） ====================
Section("13. 队友招募 / 羁绊技能 / 成长");
var companion = world.CharacterCreation.Create("human", "mage", "herbalist", "璃月");
companion.Metadata["recruitmentDef"] = new RecruitmentDef(new AlwaysTrue(), 60, true, 500, 10);

var tryRecruit = RecruitmentSystem.RecruitByBond(player, companion);
Console.WriteLine($"  初次招募: {tryRecruit.Message}");
companion.Affinity = 70;
tryRecruit = RecruitmentSystem.RecruitByBond(player, companion);
Console.WriteLine($"  好感度70后: {tryRecruit.Message}（身份: {companion.Role}）");

var bond = new BondSystem(player, companion);
Console.WriteLine($"  羁绊等级: {bond.CurrentLevel()}  羁绊技能: {string.Join("、", bond.GetBondSkillIds())}");

var companions = new List<GameCore.Unit.Unit> { companion };
var allyGrowth = new AllyGrowth(companion, world.CreateEquipment(companion));
allyGrowth.GainExp(250);
Console.WriteLine($"  队友获得250经验后: Lv{companion.Level} 剩余经验:{companion.Exp}");

// ==================== 14. 存档 / 读档 ====================
Section("14. 存档系统（自动保存 + 手动存档 + 新世界读档）");
var npcAffinities = new Dictionary<string, int> { [receptionist.Id] = receptionist.Affinity };
world.BindAutoSave(() => SaveManager.Capture(player, world.Map, home, companions, equipment,
    npcAffinities, reputation.ToSaveMap(),
    new Dictionary<string, QuestSaveData>(), new Dictionary<string, bool>(), 12345, "新手村"));

CompleteQuest(world, player, "iron_delivery");   // 触发 QUEST_COMPLETED → 自动保存到 slot 0
var manualData = SaveManager.Capture(player, world.Map, home, companions, equipment,
    npcAffinities, reputation.ToSaveMap(),
    new Dictionary<string, QuestSaveData>(), new Dictionary<string, bool>(), 12345, "新手村");
world.SaveManager.Save(1, manualData);
Console.WriteLine($"  存档列表: {string.Join(", ", world.SaveManager.ListSlots().Select(s => $"slot{s.Slot}({s.Location})"))}");

var world2 = GameWorld.Bootstrap(saveDir: saveDir, rng: new Random(7));
var state = world2.GameLoader.Load(1);
Console.WriteLine($"  新世界读档: {state.Player.Name} 种族:{state.Player.CurrentRace?.Id} " +
                  $"职业:{state.Player.CurrentClass?.Id} Lv{state.Player.Level} 金币:{state.Player.Gold}");
Console.WriteLine($"  读档标签数: {state.Player.ActiveTagIds.Count}  队友: {string.Join("、", state.Companions.Select(c => c.Name))}");
Console.WriteLine($"  读档装备: {string.Join("、", state.Equipment.GetAllEquipped().Values.Select(e => e.Name))}  " +
                  $"游戏时长: {state.PlayTime}s");

// ==================== 15. 死亡模式 ====================
Section("15. 死亡模式（Penalty：金币减半/经验惩罚/复活）");
var goldBefore = player.Gold;
var death = new DeathHandler(DeathMode.Penalty, world.SaveManager, 1);
player.Kill(null);
var outcome = death.HandleDeath(player);
Console.WriteLine($"  死亡处理结果: {outcome}");
Console.WriteLine($"  金币 {goldBefore} → {player.Gold}  复活HP: {player.Stats.Hp}/{player.Stats.MaxHp}  IsDead:{player.IsDead}");

// ==================== 16. 本地AI：NPC 动态对话 ====================
Section("16. 本地AI：NPC 动态对话（qwen2）");
Console.WriteLine($"  AI环境: {world.Ai.Describe()}");
var dialogueAi = new NpcDialogueAi(world.Ai.Chat);
if (dialogueAi.IsAvailable)
{
    var blacksmith = world.SpawnNpc("blacksmith_hans");
    blacksmith.Affinity = 30;
    var line1 = dialogueAi.GenerateLine(blacksmith, player, "玩家带着铁矿来找铁匠");
    Console.WriteLine($"  [汉斯] {line1 ?? "(生成失败，回退静态对话树)"}");
    var line2 = dialogueAi.GenerateLine(receptionist, player, "玩家刚完成屠龙任务回到公会");
    Console.WriteLine($"  [接待员] {line2 ?? "(生成失败)"}");
}
else Console.WriteLine("  本地生成模型不可用 → 保持静态对话树（离线回退）");

// ==================== 17. 本地AI：战斗解说 ====================
Section("17. 本地AI：战斗解说（qwen2）");
var narrator = new BattleNarrator(world.Ai.Chat);
if (narrator.IsAvailable)
{
    var goblin2 = world.SpawnMonster("goblin");
    var (previewDamage, previewCrit) = DamageCalculator.Calculate(player, goblin2, new GridSystem(), new Random(7));
    var narration = narrator.NarrateAttack(player, goblin2, (int)previewDamage, previewCrit);
    Console.WriteLine($"  数值结算: 玩家对哥布林伤害 {(int)previewDamage}{(previewCrit ? " 暴击" : "")}");
    Console.WriteLine($"  AI解说: {narration ?? BattleNarrator.Fallback(player, goblin2, (int)previewDamage, previewCrit)}");
}
else Console.WriteLine("  " + BattleNarrator.Fallback(player, world.SpawnMonster("goblin"), 18, true));

// ==================== 18. 本地AI：自然语言意图理解（bge-m3 嵌入） ====================
Section("18. 本地AI：意图理解（bge-m3 向量匹配）");
var intent = new IntentMatcher(world.Ai.Embeddings);
intent.AddOption("talk", "与NPC聊天、对话、询问消息");
intent.AddOption("trade", "买卖物品、交易、购买装备、出售战利品");
intent.AddOption("quest", "接受任务、委托、冒险委托");
intent.AddOption("attack", "攻击、战斗、打架");
intent.AddOption("flee", "逃跑、离开、撤退");
string[] queries = { "我想买点好装备", "有没有什么委托可以做", "快跑，打不过" };
if (intent.Build())
{
    foreach (var q in queries)
    {
        var m = intent.Match(q);
        Console.WriteLine($"  「{q}」 → {(m == null ? "未识别" : $"{m.Value.OptionId}（相似度 {m.Value.Score:F3}）")}");
    }
}
else
{
    Console.WriteLine("  嵌入模型不可用 → 关键字回退:");
    foreach (var q in queries) Console.WriteLine($"  「{q}」 → {intent.FallbackMatch(q)}");
}

// ==================== 19. 本地AI：情感评估 + 任务文案 + 平衡分析 ====================
Section("19. 本地AI：情感评估 / 任务文案 / 平衡分析（qwen2）");
var evaluator = new EmotionEvaluator(world.Ai.Chat);
var flavorAi = new QuestFlavorAi(world.Ai.Chat);
var analyzer = new BalanceAnalyzer(world.Ai.Chat);
if (evaluator.IsAvailable)
{
    var judgement = evaluator.Evaluate("玩家在黑暗洞穴中被三条巨龙包围，同伴全部倒下", player.ActiveTagIds);
    Console.WriteLine($"  情感评估: {(judgement == null ? "失败" : $"{judgement.Emotion} 强度{judgement.Intensity}")}");

    var dragonQuest = world.QuestGraph.GetNode("dragon_slay") as QuestNode;
    var flavor = flavorAi.Describe(dragonQuest, player);
    Console.WriteLine($"  任务文案: {flavor ?? dragonQuest.Data.Description}");

    // 平衡分析：先跑 8 场模拟战斗统计
    var wins = 0; var totalTurns = 0;
    for (var i = 0; i < 8; i++)
    {
        var simPlayer = world.CharacterCreation.Create("human", "warrior", "brave", $"模拟{i}");
        simPlayer.Level = 2;
        var simWolf = world.SpawnMonster("wolf");
        var simGrid = new GridSystem();
        simGrid.PlaceUnit(simPlayer, new GridPosition(1, 2, Side.Ally));
        simGrid.PlaceUnit(simWolf, new GridPosition(1, 1, Side.Enemy));
        var simBattle = new BattleManager(simGrid, world.EventBus,
            new BattleAI(new BehaviorEngine(new Random(100 + i)), new Random(100 + i)), world.EffectEngine,
            new List<GameCore.Unit.Unit> { simPlayer }, new List<GameCore.Unit.Unit> { simWolf });
        var simResult = simBattle.RunToCompletion();
        if (simResult.PlayerWin) wins++;
        totalTurns += simBattle.TurnNumber;
    }
    var report = $"模拟战斗统计：Lv2战士 vs 野狼，共8场。胜场:{wins}，胜率:{wins * 100 / 8}%，平均回合数:{totalTurns / 8.0:F1}。" +
                 $"玩家基础: HP100 ATK15 DEF8；野狼: HP60 ATK14 DEF5，残忍人格纯攻击。";
    var advice = analyzer.Analyze(report);
    Console.WriteLine($"  平衡报告输入: {report}");
    Console.WriteLine($"  AI建议: {advice ?? "(生成失败)"}");
}
else Console.WriteLine("  生成模型不可用 → 跳过AI评估环节（规则逻辑不受影响）");

// ==================== 20. 本地AI：行为决策解释 ====================
Section("20. 本地AI：行为决策解释（权重法决策 + AI心理独白）");
var explainer = new BehaviorExplainer(world.Ai.Chat);
{
    var goblin3 = world.SpawnMonster("goblin");
    var options = new List<BehaviorOption>
    {
        new("attack", "进攻", 30, null),
        new("flee", "逃跑", 40, null),
        new("defend", "防御", 10, null)
    };
    var decideEngine = new BehaviorEngine(new Random(3));
    var (chosen, weight) = decideEngine.DecideWithWeight(goblin3.ActiveTags, options);
    var candidates = options.Select(o => $"{o.Name}({decideEngine.ComputeWeight(o, goblin3.ActiveTags)})").ToList();
    Console.WriteLine($"  哥布林标签: {string.Join("、", goblin3.ActiveTagIds)}");
    Console.WriteLine($"  权重决策: {string.Join(" / ", candidates)} → 选择[{chosen.Name}] 权重{weight}");
    var monologue = explainer.Explain(goblin3.Name, goblin3.ActiveTagIds, chosen.Name, candidates);
    Console.WriteLine($"  AI独白: {monologue ?? BehaviorExplainer.Fallback(chosen.Name)}");
}

// ==================== 21. 本地AI：物品风味文本（磁盘缓存） ====================
Section("21. 本地AI：物品风味文本生成（缓存命中则不调模型）");
var flavorCache = Path.Combine(AppContext.BaseDirectory, "Data", "flavor_texts.json");
var flavorGen = new FlavorTextGenerator(world.Ai.Chat, flavorCache);
foreach (var (id, name, hint) in new[]
{
    ("iron_sword", "铁剑", "新手铁匠打造的单手剑"),
    ("lucky_ring", "幸运戒指", "据说能提升暴击的神秘戒指")
})
{
    var flavor = flavorGen.GetOrGenerate(id, name, hint);
    Console.WriteLine($"  [{name}] {flavor ?? "(离线且无缓存)"}");
}
Console.WriteLine($"  缓存文件: {flavorCache}");

Console.WriteLine();
Console.WriteLine("==================== 演示完成：全部设计文档系统已贯通 ====================");
return;

// ==================== 辅助 ====================

static int CountVisible(WorldMap map)
{
    var count = 0;
    var fog = map.CurrentFog();
    for (var y = 0; y < 50; y++)
        for (var x = 0; x < 50; x++)
            if (fog.IsVisible(x, y)) count++;
    return count;
}

static void CompleteQuest(GameWorld world, GameCore.Unit.Unit player, string questId)
{
    var quest = world.QuestGraph.GetNode(questId) as QuestNode;
    if (quest == null) { Console.WriteLine($"  任务 {questId} 不存在"); return; }

    if (quest.Data.Rewards != null)
    {
        foreach (var (key, value) in quest.Data.Rewards)
        {
            if (key == "gold") player.Gold += value;
            else player.Inventory.Add(key, value);
        }
    }
    if (quest.Data.RewardTagIds != null)
        foreach (var tagId in quest.Data.RewardTagIds)
            player.QuestTagIds.Add(tagId);

    player.RecalculateTags();
    world.EventBus.Emit(EventTypes.QuestCompleted, quest.Id, player);
    Console.WriteLine($"  完成任务[{quest.Name}]");
}

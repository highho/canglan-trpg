using GameCore;
using GameCore.AI;
using GameCore.Ally;
using GameCore.Battle;
using GameCore.Behavior;
using GameCore.Buff;
using GameCore.Craft;
using GameCore.Creation;
using GameCore.Equipment;
using GameCore.EventBus;
using GameCore.Graph;
using GameCore.Home;
using GameCore.Item;
using GameCore.Monster;
using GameCore.Npc;
using GameCore.Save;
using GameCore.Skill;
using GameCore.Social;
using GameCore.Tag;
using GameCore.Unit;
using GameCore.World;

Console.OutputEncoding = System.Text.Encoding.UTF8;

var pass = 0;
var fail = 0;

void Check(bool condition, string name)
{
    if (condition) { pass++; Console.WriteLine($"  [PASS] {name}"); }
    else { fail++; Console.WriteLine($"  [FAIL] {name}"); }
}

void Section(string title) => Console.WriteLine($"\n===== {title} =====");

string TempSaveDir() => Path.Combine(Path.GetTempPath(), "gamecore_tests_" + Guid.NewGuid().ToString("N")[..8]);

// ==================== 1. 全量配置加载 ====================
Section("1. Bootstrap 全量配置加载");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir());
    Check(TagRegistry.Instance != null, "TagRegistry 已加载");
    Check(ItemRegistry.Instance.GetAll().Count() >= 20, $"物品注册表 >=20 (实际 {ItemRegistry.Instance.GetAll().Count()})");
    Check(BuffRegistry.Instance != null, "BuffRegistry 已加载");
    Check(EquipRegistry.Instance != null, "EquipRegistry 已加载");
    Check(SkillRegistry.Instance.GetAll().Count() >= 10, "技能注册表 >=10");
    Check(world.RaceGraph.AllNodes.Count == 12, $"种族图 12 节点 (实际 {world.RaceGraph.AllNodes.Count})");
    Check(world.ClassGraph.AllNodes.Count == 8, $"职业图 8 节点 (实际 {world.ClassGraph.AllNodes.Count})");
    Check(world.QuestGraph.AllNodes.Count == 28, $"任务图 28 节点 (实际 {world.QuestGraph.AllNodes.Count})");
    Check(MonsterTemplateRegistry.Instance != null, "怪物模板已加载");
    Check(ResourceRegistry.Instance.GetAll().Count() >= 5, "采集资源 >=5");
    Check(RecipeRegistry.Instance.GetAll().Count() == 18, "配方 ==18");
    Check(BuildingRegistry.Instance.GetAllIds().Count() >= 5, "建筑定义 >=5");
    Check(NpcRegistry.Instance != null, "NPC 注册表已加载");
    Check(TraitRegistry.Instance != null, "特质注册表已加载");
}

// ==================== 2. 角色创建与标签链 ====================
Section("2. 角色创建 / 标签→属性链");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    Check(p.HasTag("人类") && p.HasTag("近战") && p.HasTag("勇敢"), "种族/职业/特质标签齐备");
    Check(p.CurrentRace.Id == "human" && p.CurrentClass.Id == "warrior", "种族/职业节点绑定");
    Check(p.Gold >= 100, "初始金币");
    Check(p.Inventory.Count("healing_potion") == 3 && p.Inventory.Count("travel_rations") == 5, "初始物品");
    Check(p.GetStat("ATK") > p.Stats.GetBase("ATK") - 0.01f, "属性含装备外基础值");
}

// ==================== 3. 情感→标签→重建 ====================
Section("3. 情感系统 → 标签激活");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    p.ApplyEmotion("恐惧", 80);
    Check(p.HasTag("恐惧"), "恐惧>50 → 标签激活");
    for (var i = 0; i < 7; i++) p.Emotion.TickDecay();   // 情感自然衰减
    p.RecalculateTags();
    Check(!p.HasTag("恐惧"), "恐惧衰减至阈值下 → 标签移除");
}

// ==================== 4. 种族进化与冲突标签 ====================
Section("4. 种族进化（任务标签 → 图迁移 → 冲突清理）");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    p.QuestTagIds.Add("光明誓约");
    p.RecalculateTags();
    p.ChangeRace(world.RaceGraph.GetNode("angel") as RaceNode);
    Check(p.HasTag("天使") && p.HasTag("神圣") && p.HasTag("光明"), "天使获得神圣/光明");
    Check(!p.HasTag("人类"), "旧种族标签消失");

    // 堕落链：黑暗标签与神圣冲突
    p.QuestTagIds.Add("堕落之印");
    p.QuestTagIds.Add("黑暗");
    p.RecalculateTags();
    p.ChangeRace(world.RaceGraph.GetNode("fallen") as RaceNode);
    Check(p.CurrentRace.Id == "fallen" && p.HasTag("堕天使"), "堕天使进化");
}

// ==================== 5. 装备 Buff 化与套装 ====================
Section("5. 装备属性 / 套装加成 / 条件门槛");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    var eq = world.CreateEquipment(p);

    var atkBefore = p.GetStat("ATK");
    var sword = new Equip(EquipRegistry.Instance.Get("iron_sword"));
    var r = eq.Equip(sword);
    Check(r.Success && p.GetStat("ATK") > atkBefore, "铁剑装备 → ATK 提升");

    // 条件门槛：暗影之刃需[暗杀]
    var shadowBlade = new Equip(EquipRegistry.Instance.Get("shadow_blade"));
    var blocked = eq.Equip(shadowBlade);
    Check(!blocked.Success, "无[暗杀]标签 → 暗影之刃被拒");
    p.QuestTagIds.Add("暗杀");
    p.QuestTagIds.Add("黑暗");
    p.RecalculateTags();
    Check(eq.Equip(shadowBlade).Success, "获得[暗杀]后装备成功");

    // 套装：暗影之刃 + 暗影之戒 = 2件 → ATK 额外加成
    var atkOne = p.GetStat("ATK");
    eq.Equip(new Equip(EquipRegistry.Instance.Get("shadow_ring")));
    Check(p.GetStat("ATK") > atkOne + 1, "2件暗影套装生效");
}

// ==================== 6. 技能树标签解锁 ====================
Section("6. 技能树解锁");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    var tree = SkillTreeRegistry.Instance.Get("warrior_tree");
    Check(tree != null, "warrior_tree 存在");
    tree.UnlockRoots();
    var unlocked = tree.GetUnlockedSkills();
    Check(unlocked.Any(s => s.Id == "slash"), "根技能斩击解锁");
    var newly = tree.CheckUnlocks(p.ActiveTagIds);
    Check(newly.Any(s => s.Id == "shield_bash"), "[近战]→盾击解锁");
    Check(!tree.GetUnlockedSkills().Any(s => s.Id == "dragon_slash"), "无[龙血觉醒] → 屠龙斩锁定");
}

// ==================== 7. 制造标签门槛 ====================
Section("7. 采集/制造标签门槛");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    var crafting = new CraftingSystem(p, world.EventBus);
    Check(crafting.GetKnownRecipes().All(r => r.Id != "iron_ingot"), "无[锻造] → 铁锭配方锁定");

    p.Inventory.Add("iron_ore", 2);
    p.QuestTagIds.Add("锻造");
    p.RecalculateTags();   // TagChanged → 自动解锁
    Check(crafting.GetKnownRecipes().Any(r => r.Id == "iron_ingot"), "获得[锻造] → 配方解锁");
    var result = crafting.Craft(RecipeRegistry.Instance.Get("iron_ingot"), p.Inventory);
    Check(result.Success && p.Inventory.Count("iron_ingot") == 1, "冶炼铁锭成功");

    var silver = new GatherPoint(ResourceRegistry.Instance.Get("silver_ore"));
    Check(silver.Gather(p) == null, "无[采集]标签 → 银矿采集失败");
}

// ==================== 8. 战斗与击杀奖励 ====================
Section("8. 回合战斗 / 击杀经验 / 掉落");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(42));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    p.Level = 3;
    var wolf = world.SpawnMonster("wolf");   // 残忍人格，不会逃跑
    var grid = new GridSystem();
    grid.PlaceUnit(p, new GridPosition(1, 2, Side.Ally));
    grid.PlaceUnit(wolf, new GridPosition(1, 1, Side.Enemy));
    var battle = new BattleManager(grid, world.EventBus,
        new BattleAI(new BehaviorEngine(new Random(42)), new Random(42)), world.EffectEngine,
        new List<GameCore.Unit.Unit> { p }, new List<GameCore.Unit.Unit> { wolf });
    var result = battle.RunToCompletion();
    Check(result.PlayerWin, "玩家单挑野狼获胜");
    Check(p.Exp > 0, $"击杀获得经验 (实际 {p.Exp})");
}

// ==================== 9. 生存惩罚 ====================
Section("9. 生存系统惩罚");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    p.Survival.Restore(0, 100, 100);
    p.Survival.Tick(BiomeType.Plains);   // 临界检查 → 生存情感标签
    p.RecalculateTags();
    p.Survival.ApplyPenalties();
    Check(p.BuffManager.HasBuff("starvation"), "饱食度0 → 饥饿虚弱Buff");
    Check(p.HasTag("饥饿"), "饥饿情感标签激活");
    p.Survival.Consume(ItemRegistry.Instance.Get("cooked_meat"));
    Check(!p.HasTag("饥饿"), "进食后饥饿标签移除");
}

// ==================== 10. 对话树 ====================
Section("10. NPC 对话树动作");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    var npc = world.SpawnNpc("guild_receptionist");
    var tree = NPCFactory.GetDialogueTree(npc);
    Check(tree != null, "接待员对话树存在");
    var ctx = new EvalContext(p.ActiveTagIds, new Dictionary<string, object>(), p, npc);
    var node = tree.GetRoot();
    while (node != null)
    {
        foreach (var a in node.OnEnterActions) a.Execute(p, npc);
        if (node.IsExit) break;
        node = tree.Next(node, ctx);
    }
    Check(p.HasTag("公会成员"), "对话授予[公会成员]标签");
}

// ==================== 11. 家园标签同步 ====================
Section("11. 家园等级标签");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    var home = new HomeBase(new MapPos(1, 1), 6, 6, world.EventBus, p);
    Check(p.HasTag("家园Lv1"), "家园建成 → [家园Lv1]");
    home.LevelUp();
    Check(p.HasTag("家园Lv2") && !p.HasTag("家园Lv1"), "升级 → 标签切换Lv2");
}

// ==================== 12. 招募与羁绊 ====================
Section("12. 招募 / 羁绊技能");
{
    var world = GameWorld.Bootstrap(saveDir: TempSaveDir(), rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "测试者");
    var c = world.CharacterCreation.Create("human", "mage", "herbalist", "队友A");
    c.Metadata["recruitmentDef"] = new RecruitmentDef(new AlwaysTrue(), 60, false, 0, 0);
    Check(!RecruitmentSystem.RecruitByBond(p, c).Success, "好感度不足招募失败");
    c.Affinity = 70;
    Check(RecruitmentSystem.RecruitByBond(p, c).Success && c.Role == UnitRole.Ally, "招募成功转队友");
    var bond = new BondSystem(p, c);
    Check(bond.CurrentLevel() == BondLevel.Friend, "好感70 → Friend");
    Check(bond.GetBondSkillIds().Contains("combo_strike"), "近战×魔能 → combo_strike");
}

// ==================== 13. 存档往返一致性 ====================
Section("13. 存档 Save/Load 往返");
{
    var dir = TempSaveDir();
    var world = GameWorld.Bootstrap(saveDir: dir, rng: new Random(1));
    var p = world.CharacterCreation.Create("human", "warrior", "brave", "存档者");
    p.QuestTagIds.Add("公会成员");
    p.RecalculateTags();
    p.Gold = 777;
    p.Level = 4;
    var eq = world.CreateEquipment(p);
    eq.Equip(new Equip(EquipRegistry.Instance.Get("iron_sword")));
    var home = new HomeBase(new MapPos(2, 2), 6, 6, world.EventBus, p);
    home.LevelUp();

    var data = SaveManager.Capture(p, world.Map, home, new List<GameCore.Unit.Unit>(),
        eq, new Dictionary<string, int>(), new Dictionary<string, Dictionary<string, int>>(),
        new Dictionary<string, QuestSaveData>(), new Dictionary<string, bool>(), 999, "测试地");
    Check(world.SaveManager.Save(3, data), "保存 slot3");

    var world2 = GameWorld.Bootstrap(saveDir: dir, rng: new Random(2));
    var state = world2.GameLoader.Load(3);
    Check(state.Player.Name == "存档者", "玩家名字恢复");
    Check(state.Player.CurrentRace.Id == "human" && state.Player.CurrentClass.Id == "warrior", "种族/职业恢复");
    Check(state.Player.Gold == 777 && state.Player.Level == 4, "金币/等级恢复");
    Check(state.Player.HasTag("公会成员") && state.Player.HasTag("勇敢"), "任务/特质标签重建");
    Check(state.Player.HasTag("家园Lv2"), "家园等级标签重建");
    Check(state.Equipment.Get(EquipSlot.Weapon)?.Id == "iron_sword", "装备恢复并重建属性");
    Check(state.PlayTime == 999, "游戏时长恢复");
}

// ==================== 14. 本地AI服务 ====================
Section("14. 本地AI（GGUF直连推理 + 离线回退）");
{
    // 离线回退：Null 实现保证游戏无AI环境完全可玩
    var offlineCtx = AiContext.Create(AiSettings.Disabled);
    Check(!offlineCtx.IsChatAvailable && !offlineCtx.IsEmbeddingAvailable, "Disabled 配置 → Null 实现");
    Check(offlineCtx.Backend == "None", "Disabled 后端标识为 None");
    var offlineAi = new NpcDialogueAi(offlineCtx.Chat);
    Check(offlineAi.GenerateLine(null, null) == null, "离线对话生成返回 null（调用方回退对话树）");

    // 向量数学
    Check(Math.Abs(VectorMath.Cosine(new float[] { 1, 0 }, new float[] { 1, 0 }) - 1f) < 1e-4, "余弦相似度同向=1");
    Check(Math.Abs(VectorMath.Cosine(new float[] { 1, 0 }, new float[] { 0, 1 })) < 1e-4, "余弦相似度正交=0");

    // 路径工具（llama.cpp 不支持非 ASCII 路径）
    Check(ModelFileLocator.IsAsciiPath(@"C:\GameModels\qwen2-7b-q4.gguf"), "ASCII 路径判定：纯英文=True");
    Check(!ModelFileLocator.IsAsciiPath(@"C:\中文目录\a.gguf"), "ASCII 路径判定：中文=False");

    // 关键字回退匹配（离线可用）
    var fallbackMatcher = new IntentMatcher(offlineCtx.Embeddings);
    Check(fallbackMatcher.FallbackMatch("我想买装备") == "trade", "关键字回退: 买装备→trade");
    Check(fallbackMatcher.FallbackMatch("有什么任务") == "quest", "关键字回退: 任务→quest");

    // JSON 提取工具
    Check(EmotionEvaluator.ExtractJson("好的，结果是{\"emotion\":\"恐惧\",\"intensity\":75}，希望有用") == "{\"emotion\":\"恐惧\",\"intensity\":75}",
        "JSON 提取器能剥离前后文");

    // 决策解释/风味文本的离线回退与缓存逻辑（不依赖模型）
    Check(BehaviorExplainer.Fallback("逃跑") == "权衡再三，它选择了：逃跑。", "决策解释离线回退文案");
    var cacheFile = Path.Combine(Path.GetTempPath(), $"flavor_test_{Guid.NewGuid():N}.json");
    var offlineFlavor = new FlavorTextGenerator(offlineCtx.Chat, cacheFile);
    Check(offlineFlavor.GetOrGenerate("x", "测试剑") == null, "离线且无缓存 → 风味文本返回 null");
    File.WriteAllText(cacheFile, "{\"x\":\"缓存文案\"}");
    Check(offlineFlavor.GetOrGenerate("x", "测试剑") == "缓存文案", "缓存命中时不调模型直接返回");
    File.Delete(cacheFile);

    // GGUF 直连集成（模型文件存在时执行，否则跳过）
    var directCtx = AiContext.Create(new AiSettings { Mode = AiInferenceMode.Direct });
    if (directCtx.IsChatAvailable)
    {
        Check(directCtx.Backend.StartsWith("Direct"), $"Direct 模式后端标识 ({directCtx.Backend})");
        var reply = directCtx.Chat.Chat("你是回声器", "只回复两个字：收到", jsonMode: false);
        Check(!string.IsNullOrWhiteSpace(reply), $"GGUF直连 qwen2 对话联通 (回复: {reply})");

        var evaluator = new EmotionEvaluator(directCtx.Chat);
        var judgement = evaluator.Evaluate("挚友在自己眼前被巨龙吃掉", new HashSet<string> { "勇敢" });
        Check(judgement != null && judgement.Intensity >= 50, $"直连情感评估联通 ({judgement?.Emotion}{judgement?.Intensity})");
    }
    else Console.WriteLine("  [SKIP] 未找到 GGUF 生成模型文件，跳过直连对话测试");

    if (directCtx.IsEmbeddingAvailable)
    {
        var emb = directCtx.Embeddings.Embed(new[] { "买卖交易", "战斗攻击" });
        Check(emb != null && emb.Length == 2 && emb[0].Length > 0, "GGUF直连 bge-m3 嵌入联通");

        var matcher = new IntentMatcher(directCtx.Embeddings);
        matcher.AddOption("trade", "买卖物品、交易、购买装备");
        matcher.AddOption("attack", "攻击、战斗、打架");
        var m = matcher.Match("我想买点装备");
        Check(m != null && m.Value.OptionId == "trade", $"直连语义意图匹配: 买装备→trade ({m?.Score:F3})");
    }
    else Console.WriteLine("  [SKIP] 未找到 GGUF 嵌入模型文件，跳过直连嵌入测试");
}

// ==================== 汇总 ====================
Console.WriteLine($"\n==================== 测试结果: {pass} 通过 / {fail} 失败 ====================");
return fail == 0 ? 0 : 1;

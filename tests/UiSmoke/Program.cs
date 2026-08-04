using GameApp.ViewModels;
using GameCore.World;

Console.OutputEncoding = System.Text.Encoding.UTF8;

var vm = new MainViewModel();

void Do(string name, Action act)
{
    try { act(); }
    catch (Exception ex) { Console.WriteLine($"[命令异常] {name}: {ex.Message}"); }
}

// ===== 页面流程：开始 → 存档位 → 创建 → 游戏 =====
Do("NewGame", () => vm.NewGame.Execute(null));                       // Start → SaveSelect
Do("PickSlot1", () => vm.PickSlot.Execute("1"));                     // SaveSelect → Creation

vm.CreationNameInput = "阿岚";
Do("SelectRace", () => vm.SelectRace.Execute("人类"));
Do("SelectClass", () => vm.SelectClass.Execute("战士"));
Do("SelectTrait", () => vm.SelectTrait.Execute("勇敢"));
Do("BeginAdventure", () => vm.BeginAdventure.Execute(null));         // Creation → Game（自动存档）

// ===== 位置感知行动：行动随坐标变化，先寻路再打怪/采集 =====
var pos = new MapPos(25, 25);   // 出生点 = 新手村中心

// 出生点断言：附近按钮只含近郊点，落地不能打全图的怪
var spawnButtons = vm.MonsterOptions.Count;
var gatherButtons = vm.ResourceOptions.Count;
var allTemplates = GameCore.Monster.MonsterTemplateRegistry.Instance.GetAll().Count();
var allResources = GameCore.Craft.ResourceRegistry.Instance.GetAll().Count();
Console.WriteLine($"[位置断言] 出生点攻击按钮 {spawnButtons}/{allTemplates}，采集按钮 {gatherButtons}/{allResources}");

// 走到最近的怪物刷新点（村内无怪，需走出村庄；PopulateWorld 保证郊外点存在）
TerrainFeature nearest(FeatureType type) => vm.World.Map.Features
    .Where(f => f.Type == type)
    .OrderBy(f => f.Pos.DistanceTo(pos))
    .First();

void WalkTo(MapPos target)
{
    while (pos.X != target.X || pos.Y != target.Y)
    {
        var dir = pos.X < target.X ? "东" : pos.X > target.X ? "西" : pos.Y < target.Y ? "南" : "北";
        vm.QuickCommand.Execute(dir);
        var (dx, dy) = dir switch { "东" => (1, 0), "西" => (-1, 0), "南" => (0, 1), _ => (0, -1) };
        pos = new MapPos(pos.X + dx, pos.Y + dy);
        Thread.Sleep(20);
    }
}

var spawn = nearest(FeatureType.MonsterSpawn);
Console.WriteLine($"[寻路] 最近怪物刷新点（{spawn.Pos.X},{spawn.Pos.Y}）模板 {spawn.Id}，距离 {spawn.Pos.DistanceTo(new MapPos(25, 25)):F1}");

// 距离断言：先攻击一个不在附近的怪 → 应被位置校验拒绝
var farTemplate = GameCore.Monster.MonsterTemplateRegistry.Instance.GetAll()
    .First(m => vm.World.Map.FindNearby(pos, 4).All(f => f.Id != m.Id));
vm.QuickCommand.Execute("攻击 " + farTemplate.Name);
var guarded = vm.NarrationLines.Any(l => l.Text.Contains("不在你附近"));
Console.WriteLine($"[位置断言] 远处怪「{farTemplate.Name}」被拒绝：{guarded}");

WalkTo(spawn.Pos);
Console.WriteLine($"[位置断言] 走近后攻击按钮数：{vm.MonsterOptions.Count}（应 > 0）");
vm.QuickCommand.Execute("攻击 " + GameCore.Monster.MonsterTemplateRegistry.Instance.Get(spawn.Id).Name);
Thread.Sleep(60);

var gather = nearest(FeatureType.GatherPoint);
Console.WriteLine($"[寻路] 最近采集点（{gather.Pos.X},{gather.Pos.Y}）资源 {gather.Id}，距离 {gather.Pos.DistanceTo(pos):F1}");
WalkTo(gather.Pos);
vm.QuickCommand.Execute("采集 " + GameCore.Craft.ResourceRegistry.Instance.Get(gather.Id).Name);
Thread.Sleep(60);

// ===== 游戏页其余行动（野外的随身行动） =====
string[] actions =
{
    "吃 旅行干粮",
    "北",
    "查看",
    "传闻",
    "装备",
    "装备 铁剑",
    "技能",
    "解锁技能",
    "配方",
    "存档",
    "状态",
};
foreach (var a in actions)
{
    vm.QuickCommand.Execute(a);
    Thread.Sleep(60);
}

// 空间门槛断言：走到地图角落（确定远离村庄/水源/家园），喝水/布告板/家园都应被拒
WalkTo(new MapPos(2, 2));
vm.QuickCommand.Execute("喝水");
var drinkGuarded = vm.NarrationLines.Any(l => l.Text.Contains("附近没有水源"));
vm.QuickCommand.Execute("任务");
var boardGuarded = vm.NarrationLines.Any(l => l.Text.Contains("布告板立在新手村"));
vm.QuickCommand.Execute("家园");
var homeGuarded = vm.NarrationLines.Any(l => l.Text.Contains("你不在家园"));
Console.WriteLine($"[位置断言] 野外喝水被拒：{drinkGuarded}，野外看布告板被拒：{boardGuarded}，野外看家园被拒：{homeGuarded}");

// 回村后的村庄行动（NPC/布告板/水井/家园都在村内）
WalkTo(new MapPos(25, 25));
foreach (var a in new[] { "任务", "完成 粮仓除鼠", "喝水", "家园", "交谈 村长罗万", "招募 猎人奥达" })
{
    vm.QuickCommand.Execute(a);
    Thread.Sleep(60);
}

// ===== 主菜单 → 读档回游戏 =====
Do("SaveAndMenu", () => vm.SaveAndMenu.Execute(null));               // Game → Start（自动存档）
Do("LoadGame", () => vm.LoadGame.Execute(null));                     // Start → SaveSelect
Do("PickSlotLoad", () => vm.PickSlot.Execute("1"));                  // SaveSelect → Game（读档）

var errors = vm.NarrationLines.Where(l => l.Kind == NarrationKind.Error).ToList();
Console.WriteLine($"最终页面：{vm.CurrentPage}");
Console.WriteLine($"叙事行总数：{vm.NarrationLines.Count}");
Console.WriteLine($"错误行数量：{errors.Count}");
foreach (var e in errors) Console.WriteLine("  [错误] " + e.Text);

Console.WriteLine("\n===== 叙事尾部 20 行 =====");
foreach (var line in vm.NarrationLines.TakeLast(20))
    Console.WriteLine($"[{line.Kind}] {line.Text}");

var ok = errors.Count == 0 && vm.CurrentPage == "Game" && vm.IsPlayerReady
    && guarded && spawnButtons == 0
    && gatherButtons == 1 && vm.ResourceOptions[0].Contains("清泉")   // 出生点唯一采集按钮 = 村里水井
    && drinkGuarded && boardGuarded && homeGuarded;
Console.WriteLine(ok ? "\n[UI冒烟] 通过" : "\n[UI冒烟] 失败");
return ok ? 0 : 1;

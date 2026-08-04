using System.Collections.ObjectModel;
using System.Text;
using GameCore;
using GameCore.AI;
using GameCore.Ally;
using GameCore.Battle;
using GameCore.Behavior;
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
using GameCore.World;
using Unit = GameCore.Unit.Unit;

namespace GameApp.ViewModels;

/// <summary>叙事行类别（决定着色）。</summary>
public enum NarrationKind { Input, Narration, System, Dialogue, Combat, Reward, Error }

/// <summary>一行叙事文本。</summary>
public sealed record NarrationLine(string Text, NarrationKind Kind);

/// <summary>
/// MainViewModel — 文字冒险叙事引擎 + 指令解析器。
/// 铁律：所有游戏逻辑走规则系统；AI 仅用于自由对话文本生成，失败自动回退，永不阻塞。
/// </summary>
public sealed class MainViewModel : ViewModelBase
{
    // ==================== 核心世界 ====================
    public GameWorld World { get; private set; }
    private Unit _player;
    private EquipmentManager _equipment;
    private CraftingSystem _crafting;
    private HomeBase _home;
    private SkillTree _skillTree;
    private CooldownManager _cooldowns;
    private readonly ReputationSystem _reputation = new();
    private AdventureGuild _guild;
    private readonly Dictionary<string, Unit> _npcInstances = new();
    private readonly List<Unit> _companions = new();
    private List<QuestNode> _questCache = new();
    private List<Recipe> _recipeCache = new();
    private int _buildX = 1, _buildY = 1;
    private string _lastTalkNpcId;
    private readonly Dictionary<string, GatherPoint> _gatherPoints = new();   // 资源id → 采集点实例（储量/冷却持久）
    private const int NearbyRange = 4;   // 怪物/采集点的可行动半径
    private const int NpcRange = 4;      // NPC 可交谈半径（新手村范围）

    // ==================== 角色创建状态机 ====================
    private int _creationStage;               // 0=未开始 1=选种族 2=选职业 3=选特质
    private string _creationName = "旅人";
    private List<RaceNode> _creationRaces = new();
    private List<ClassNode> _creationClasses = new();
    private List<TraitDef> _creationTraits = new();
    private RaceNode _pickedRace;
    private ClassNode _pickedClass;

    // ==================== 叙事流 ====================
    public ObservableCollection<NarrationLine> NarrationLines { get; } = new();

    // ==================== 界面状态（TRPG 组件式交互） ====================
    private bool _isCreating;
    public bool IsCreating { get => _isCreating; private set => Set(ref _isCreating, value); }

    private bool _isPlayerReady;
    public bool IsPlayerReady { get => _isPlayerReady; private set => Set(ref _isPlayerReady, value); }

    private string _creationPrompt = "";
    public string CreationPrompt { get => _creationPrompt; private set => Set(ref _creationPrompt, value); }

    /// <summary>创建流程当前步骤的选项按钮（种族/职业/特质）。</summary>
    public ObservableCollection<string> CreationChoices { get; } = new();

    // 行动按钮选项（每项即完整指令，点击直接执行）
    public ObservableCollection<string> NpcOptions { get; } = new();
    public ObservableCollection<string> RecruitOptions { get; } = new();
    public ObservableCollection<string> MonsterOptions { get; } = new();
    public ObservableCollection<string> ResourceOptions { get; } = new();
    public ObservableCollection<string> EquipOptions { get; } = new();
    public ObservableCollection<string> BuildingOptions { get; } = new();
    public ObservableCollection<string> FoodOptions { get; } = new();
    public ObservableCollection<string> RecipeOptions { get; } = new();
    public ObservableCollection<string> QuestOptions { get; } = new();

    private static void Sync(ObservableCollection<string> col, IEnumerable<string> items)
    {
        col.Clear();
        foreach (var i in items) col.Add(i);
    }

    /// <summary>静态选项：与坐标无关的注册表内容（装备/建筑）。</summary>
    private void FillStaticOptions()
    {
        Sync(EquipOptions, EquipRegistry.Instance.GetAll().Select(e => "装备 " + e.Name));
        Sync(BuildingOptions, BuildingRegistry.Instance.GetAllIds().Select(id => "建造 " + id));
    }

    /// <summary>随坐标变化的选项：以玩家位置为中心查询地图地形物（怪物刷新点/采集点/NPC）。</summary>
    private void RefreshNearbyOptions()
    {
        if (_player == null || World?.Map == null) return;
        var nearby = World.Map.FindNearby(_player.WorldPos, NearbyRange);
        Sync(MonsterOptions, nearby.Where(f => f.Type == FeatureType.MonsterSpawn)
            .Select(f => f.Id).Distinct()
            .Where(id => MonsterTemplateRegistry.Instance.TryGet(id, out _))
            .Select(id => "攻击 " + MonsterTemplateRegistry.Instance.Get(id).Name));
        Sync(ResourceOptions, nearby.Where(f => f.Type == FeatureType.GatherPoint)
            .Select(f => f.Id).Distinct()
            .Where(id => ResourceRegistry.Instance.TryGet(id, out _))
            .Select(id => "采集 " + ResourceRegistry.Instance.Get(id).Name));
        var npcNearby = World.Map.FindNearby(_player.WorldPos, NpcRange)
            .Where(f => f.Type == FeatureType.NpcSpawn).Select(f => f.Id).Distinct().ToList();
        var npcDefs = NpcRegistry.Instance.GetAll().Where(n => npcNearby.Contains(n.Id)).ToList();
        Sync(NpcOptions, npcDefs.Select(n => "交谈 " + n.Name));
        Sync(RecruitOptions, npcDefs.Select(n => "招募 " + n.Name));
    }

    /// <summary>玩家坐标附近是否存在指定类型的地形物（Id 匹配）。</summary>
    private bool IsNearby(FeatureType type, string id, int range)
        => _player != null && World.Map.FindNearby(_player.WorldPos, range).Any(f => f.Type == type && f.Id == id);

    /// <summary>是否在家园范围内（家园相关行动的空间门槛）。</summary>
    private bool NearHome() => _home != null && _player.WorldPos.DistanceTo(_home.Position) <= NearbyRange + 1;

    /// <summary>从玩家当前位置指向目标坐标的方位描述（如「东北方向（约 7 步）」）。</summary>
    private string DirectionTo(MapPos to)
    {
        var dx = to.X - _player.WorldPos.X;
        var dy = to.Y - _player.WorldPos.Y;
        var ns = dy < 0 ? "北" : dy > 0 ? "南" : "";
        var ew = dx > 0 ? "东" : dx < 0 ? "西" : "";
        var dir = ns + ew;
        var dist = (int)Math.Ceiling(_player.WorldPos.DistanceTo(to));
        return dir.Length == 0 ? "就在脚下" : $"{dir}方向（约 {dist} 步）";
    }

    /// <summary>传闻：指引玩家找到最近的可用目标（解决「不知道该往哪走」）。</summary>
    private void CmdRumor()
    {
        if (!EnsurePlayer()) return;
        Narrate("—— 风声里传来一些传闻 ——", NarrationKind.System);
        var features = World.Map.Features;
        void Hint(FeatureType type, string label, Func<string, string> nameOf = null)
        {
            var f = features.Where(x => x.Type == type && (nameOf == null || nameOf(x.Id) != null))
                .OrderBy(x => x.Pos.DistanceTo(_player.WorldPos)).FirstOrDefault();
            if (f == null) return;
            var name = nameOf != null ? nameOf(f.Id) : f.Id;
            var near = f.Pos.DistanceTo(_player.WorldPos) <= NearbyRange;
            Narrate(near ? $"{label}「{name}」就在你身边。" : $"{label}「{name}」在{DirectionTo(f.Pos)}。", NarrationKind.System);
        }
        if (!IsNearby(FeatureType.Building, "布告板", NearbyRange))
            Hint(FeatureType.Building, "公会布告板（接委托）");
        if (!NearHome()) Narrate($"你的家园在{DirectionTo(_home.Position)}。", NarrationKind.System);
        Hint(FeatureType.MonsterSpawn, "怪物踪迹",
            id => MonsterTemplateRegistry.Instance.TryGet(id, out var t) ? t.Name : null);
        Hint(FeatureType.GatherPoint, "采集点",
            id => ResourceRegistry.Instance.TryGet(id, out var r) ? r.Name : null);
        Narrate("提示：用「东 / 南 / 西 / 北」按钮移动，行动会随坐标变化。", NarrationKind.System);
    }

    /// <summary>动态选项：随角色状态与坐标变化（行囊食物/已学配方/可接委托/附近目标）。</summary>
    private void RefreshOptions()
    {
        if (_player == null) return;
        Sync(FoodOptions, _player.Inventory.Stacks
            .Where(s => s.Def.Type == ItemType.Consumable && s.Def.Nutrition > 0)
            .Select(s => s.Def.Name).Distinct().Select(n => "吃 " + n));
        Sync(RecipeOptions, _crafting == null
            ? Array.Empty<string>()
            : _crafting.GetKnownRecipes().Select(r => "制造 " + r.Name));
        Sync(QuestOptions, _questCache.Select(q => "完成 " + q.Name));
        RefreshNearbyOptions();
    }

    public void Narrate(string text, NarrationKind kind = NarrationKind.Narration)
    {
        NarrationLines.Add(new NarrationLine(text, kind));
        while (NarrationLines.Count > 600) NarrationLines.RemoveAt(0);
    }

    public MainViewModel()
    {
        try
        {
            var saveDir = Path.Combine(AppContext.BaseDirectory, "saves");
            World = GameWorld.Bootstrap(saveDir: saveDir, rng: new Random());
            _guild = new AdventureGuild(World.QuestGraph, _reputation);
            FillStaticOptions();
            _ = InitAiAsync();   // 后台探测 AI 后端，避免阻塞 UI 线程启动
            RefreshSlotsInfo();
            RefreshSlotTexts();
        }
        catch (Exception ex)
        {
            Narrate("世界启动失败：" + ex.Message, NarrationKind.Error);
        }
    }

    private void NarrateIntro()
    {
        Narrate("══════════════════════════════════════", NarrationKind.System);
        Narrate("　苍岚大陆 —— 标签驱动的文字冒险", NarrationKind.System);
        Narrate("══════════════════════════════════════", NarrationKind.System);
        Narrate("你在暮色中抵达边境的新手村。炊烟从茅屋顶上升起，铁匠铺的锤声叮当作响，酒馆里传出嘈杂的笑语。", NarrationKind.Narration);
        Narrate("村口的告示板上钉着公会的委托，远处的森林黑得深不见底。", NarrationKind.Narration);
        Narrate($"世界规模：种族 {World.RaceGraph.AllNodes.Count} / 职业 {World.ClassGraph.AllNodes.Count} / 任务 {World.QuestGraph.AllNodes.Count} / NPC {NpcRegistry.Instance.GetAll().Count()} / 怪物 {MonsterTemplateRegistry.Instance.GetAll().Count()}", NarrationKind.System);
        Narrate("点击下方的行动按钮探索世界；在「交谈」里点选人物与话题。", NarrationKind.System);
    }

    /// <summary>后台探测 AI 后端（Ollama 探测可能阻塞数百毫秒至超时），完成后回 UI 线程刷新状态栏。</summary>
    private async Task InitAiAsync()
    {
        AiStatusText = "本地AI探测中…";
        string desc;
        try { desc = await Task.Run(() => World.Ai.Describe()); }
        catch (Exception ex) { AiStatusText = "AI探测失败：" + ex.Message; return; }
        AiStatusText = desc;
    }

    // ==================== 页面流程（开始 → 存档 → 创建 → 游戏） ====================
    private string _currentPage = "Start";
    public string CurrentPage { get => _currentPage; private set { if (Set(ref _currentPage, value)) RaisePagesChanged(); } }

    public bool IsStartPage => CurrentPage == "Start";
    public bool IsSavePage => CurrentPage == "SaveSelect";
    public bool IsCreationPage => CurrentPage == "Creation";
    public bool IsGamePage => CurrentPage == "Game";

    private void RaisePagesChanged()
    {
        OnPropertyChanged(nameof(IsStartPage));
        OnPropertyChanged(nameof(IsSavePage));
        OnPropertyChanged(nameof(IsCreationPage));
        OnPropertyChanged(nameof(IsGamePage));
    }

    private int _saveMode;          // 0=新建 1=读档
    private int _selectedSlot = 1;
    public string SavePageTitle => _saveMode == 1 ? "读取存档" : "开始新游戏 —— 选择存档位";

    public RelayCommand NewGame => new(() => { _saveMode = 0; RefreshSlotTexts(); OnPropertyChanged(nameof(SavePageTitle)); CurrentPage = "SaveSelect"; });
    public RelayCommand LoadGame => new(() => { _saveMode = 1; RefreshSlotTexts(); OnPropertyChanged(nameof(SavePageTitle)); CurrentPage = "SaveSelect"; });
    public RelayCommand GoBackSavePage => new(() => CurrentPage = "SaveSelect");
    public RelayCommand BackToStart => new(() => CurrentPage = "Start");
    public RelayCommand ExitApp => new(() =>
    {
        if (Avalonia.Application.Current?.ApplicationLifetime is Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime d)
            d.Shutdown();
    });

    public RelayCommand PickSlot => new(p =>
    {
        if (!int.TryParse(p?.ToString(), out var slot) || slot < 1 || slot > 3) return;
        _selectedSlot = slot;
        if (_saveMode == 1)
        {
            if (!DoLoad(slot)) return;
            CurrentPage = "Game";
        }
        else
        {
            ResetCreationPage();
            CurrentPage = "Creation";
        }
    });

    private string _slot1Text = "存档位 1 —— 空", _slot2Text = "存档位 2 —— 空", _slot3Text = "存档位 3 —— 空";
    public string Slot1Text { get => _slot1Text; private set => Set(ref _slot1Text, value); }
    public string Slot2Text { get => _slot2Text; private set => Set(ref _slot2Text, value); }
    public string Slot3Text { get => _slot3Text; private set => Set(ref _slot3Text, value); }

    private void RefreshSlotTexts()
    {
        if (World == null) return;
        var map = World.SaveManager.ListSlots().ToDictionary(s => s.Slot, s => s.Location);
        Slot1Text = "存档位 1 —— " + (map.TryGetValue(1, out var a) ? a : "空");
        Slot2Text = "存档位 2 —— " + (map.TryGetValue(2, out var b) ? b : "空");
        Slot3Text = "存档位 3 —— " + (map.TryGetValue(3, out var c) ? c : "空");
    }

    /// <summary>游戏页「主菜单」：自动存档后回到开始界面。</summary>
    public RelayCommand SaveAndMenu => new(() =>
    {
        if (_player != null)
        {
            World.SaveManager.Save(_selectedSlot, CaptureCurrent());
            RefreshSlotsInfo();
            RefreshSlotTexts();
        }
        CurrentPage = "Start";
    });

    /// <summary>快捷话题按钮 → AI 自由对话（代替自由输入）。</summary>
    public RelayCommand Say => new(async p => { if (p is string s) await AiFreeTalkAsync(s); });

    // ==================== 创建页（组件化三选） ====================
    private string _creationNameInput = "旅人";
    public string CreationNameInput { get => _creationNameInput; set => Set(ref _creationNameInput, value); }

    private string _creationSummary = "血脉：未定　道路：未定　特质：未定";
    public string CreationSummary { get => _creationSummary; private set => Set(ref _creationSummary, value); }

    private TraitDef _pickedTrait;

    public ObservableCollection<string> RaceChoices { get; } = new();
    public ObservableCollection<string> ClassChoices { get; } = new();
    public ObservableCollection<string> TraitChoices { get; } = new();

    /// <summary>进入创建页：重置三选状态并填充选项。</summary>
    private void ResetCreationPage()
    {
        _pickedRace = null;
        _pickedClass = null;
        _pickedTrait = null;
        Sync(RaceChoices, World.CharacterCreation.GetAvailableRaces().Select(r => r.Data.Name));
        Sync(ClassChoices, World.CharacterCreation.GetAvailableClasses().Select(c => c.Data.Name));
        Sync(TraitChoices, TraitRegistry.Instance.GetAll().Select(t => t.Name));
        CreationSummary = "血脉：未定　道路：未定　特质：未定";
    }

    public RelayCommand SelectRace => new(p =>
    {
        if (p is not string name) return;
        _pickedRace = World.RaceGraph.AllNodes.OfType<RaceNode>().FirstOrDefault(r => r.Data.Name == name);
        RefreshTraitChoices();
        UpdateCreationSummary();
    });

    public RelayCommand SelectClass => new(p =>
    {
        if (p is not string name) return;
        _pickedClass = World.ClassGraph.AllNodes.OfType<ClassNode>().FirstOrDefault(c => c.Data.Name == name);
        RefreshTraitChoices();
        UpdateCreationSummary();
    });

    public RelayCommand SelectTrait => new(p =>
    {
        if (p is not string name) return;
        _pickedTrait = TraitRegistry.Instance.GetAll().FirstOrDefault(t => t.Name == name);
        UpdateCreationSummary();
    });

    private void RefreshTraitChoices()
    {
        if (_pickedRace != null && _pickedClass != null)
            Sync(TraitChoices, World.CharacterCreation.GetAvailableTraits(_pickedRace.Data, _pickedClass.Data).Select(t => t.Name));
        if (_pickedTrait != null && !TraitChoices.Contains(_pickedTrait.Name)) _pickedTrait = null;
    }

    private void UpdateCreationSummary()
        => CreationSummary = $"血脉：{_pickedRace?.Data.Name ?? "未定"}　道路：{_pickedClass?.Data.Name ?? "未定"}　特质：{_pickedTrait?.Name ?? "未定"}";

    /// <summary>创建页「开始冒险」：校验三选 → 建档 → 进入游戏页并自动存档。</summary>
    public RelayCommand BeginAdventure => new(() =>
    {
        if (_pickedRace == null || _pickedClass == null || _pickedTrait == null)
        {
            CreationSummary = "请先选定血脉、道路与出身特质三项。";
            return;
        }
        _creationName = (CreationNameInput ?? "").Trim();
        if (_creationName.Length == 0) _creationName = "旅人";
        FinishCreation(_pickedTrait);
    });

    // ==================== 指令输入 ====================
    private string _commandInput = "";
    public string CommandInput { get => _commandInput; set => Set(ref _commandInput, value); }

    private bool _isAiBusy;
    public bool IsAiBusy { get => _isAiBusy; private set => Set(ref _isAiBusy, value); }

    public RelayCommand SubmitCommand => new(async () => await SubmitAsync());
    public RelayCommand QuickCommand => new(async p => { if (p is string s) await ExecuteLineAsync(s); });

    private async Task SubmitAsync()
    {
        var line = (CommandInput ?? "").Trim();
        if (line.Length == 0) return;
        CommandInput = "";
        await ExecuteLineAsync(line);
    }

    private async Task ExecuteLineAsync(string line)
    {
        Narrate("＞ " + line, NarrationKind.Input);
        try
        {
            await ExecuteCommandAsync(line);
            RefreshPanels();
        }
        catch (Exception ex)
        {
            Narrate("指令执行异常：" + ex.Message, NarrationKind.Error);
        }
    }

    // ==================== 指令解析器 ====================
    private async Task ExecuteCommandAsync(string raw)
    {
        raw = raw.Replace('　', ' ');   // 全角空格归一化，避免中文输入法下指令失配

        // 创建流程中：仅「取消」可退出，其余输入一律作为选择项
        if (_creationStage > 0)
        {
            if (raw.Trim() is "取消" or "cancel") { ResetCreation(); return; }
            HandleCreationChoice(raw);
            return;
        }

        var parts = raw.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length == 0) return;
        var head = parts[0];
        var arg = raw.Length > head.Length ? raw[head.Length..].Trim() : "";

        switch (head)
        {
            case "帮助": case "help": CmdHelp(); break;
            case "创建": CmdCreate(arg); break;
            case "取消": Narrate("当前没有可取消的操作。", NarrationKind.System); break;
            case "状态": CmdStatus(); break;
            case "背包": CmdInventory(); break;
            case "标签": CmdTags(); break;
            case "装备": CmdEquip(arg); break;
            case "东": case "南": case "西": case "北": Move(head); break;
            case "前往": case "去": case "走":
                if (arg.Length > 0) Move(arg[..1]); else Narrate("去哪里？（东 / 南 / 西 / 北）", NarrationKind.System);
                break;
            case "查看": case "环顾": CmdLook(); break;
            case "交谈": case "对话": case "找": CmdTalk(arg); break;
            case "攻击": case "讨伐": case "战斗": CmdFight(arg); break;
            case "采集": CmdGather(arg); break;
            case "吃": CmdEat(arg); break;
            case "喝水": case "饮水": CmdDrink(); break;
            case "制造": CmdCraft(arg); break;
            case "配方": CmdRecipes(); break;
            case "任务": CmdQuestList(); break;
            case "传闻": CmdRumor(); break;
            case "完成": CmdQuestComplete(arg); break;
            case "技能": CmdSkills(); break;
            case "解锁技能": CmdUnlockSkills(); break;
            case "建造": CmdBuild(arg); break;
            case "家园": CmdHome(); break;
            case "家园升级": CmdHomeLevelUp(); break;
            case "招募": CmdRecruit(arg); break;
            case "存档": CmdSave(); break;
            case "读档": CmdLoad(); break;
            case "存档列表": CmdSlots(); break;
            default: await AiFreeTalkAsync(raw); break;   // 未识别指令 → AI 自由对话
        }
    }

    private void CmdHelp()
    {
        Narrate("—— 指令一览 ——", NarrationKind.System);
        Narrate("创建 名字 …… 建档（随后按提示依次选择种族/职业/特质）", NarrationKind.System);
        Narrate("状态 / 背包 / 标签 / 装备[名称] / 技能 / 配方 / 家园", NarrationKind.System);
        Narrate("东 / 南 / 西 / 北 …… 移动；查看 …… 环顾四周", NarrationKind.System);
        Narrate("交谈 [某人] …… 触发对话树；攻击 [怪物] …… 遭遇战", NarrationKind.System);
        Narrate("采集 [资源] / 吃 [食物] / 喝水 / 制造 [配方] / 建造 [建筑] / 家园升级", NarrationKind.System);
        Narrate("任务 / 完成 [任务] / 解锁技能 / 招募 [某人] / 存档 / 读档 / 存档列表", NarrationKind.System);
        Narrate("输入其他任意话语，将直接说给最近交谈过的人（本地AI应答，不可用时回退）。", NarrationKind.System);
    }

    // ==================== 创建角色 ====================
    private void CmdCreate(string name)
    {
        if (_player != null) { Narrate("你已有角色。如需重开，请重启应用或读档。", NarrationKind.System); return; }
        _creationName = name.Length == 0 ? "旅人" : name;
        _creationRaces = World.CharacterCreation.GetAvailableRaces().ToList();
        _creationStage = 1;
        IsCreating = true;
        CreationPrompt = $"第 1/3 步 · 选择种族（{_creationName}，进阶种族需在冒险中进化）";
        Sync(CreationChoices, _creationRaces.Select(r => r.Name));
        Narrate($"很好，{_creationName}。命运的织机开始转动。", NarrationKind.Narration);
        Narrate("选择你的种族（输入编号或名称）：", NarrationKind.System);
        for (var i = 0; i < _creationRaces.Count; i++)
            Narrate($"  {i + 1}. {_creationRaces[i].Name}", NarrationKind.System);
    }

    private void HandleCreationChoice(string input)
    {
        switch (_creationStage)
        {
            case 1:
                _pickedRace = PickOne(_creationRaces, input, n => n.Name, n => n.Id);
                if (_pickedRace == null) { Narrate("请输入列表中的编号或名称。", NarrationKind.System); return; }
                _creationClasses = World.CharacterCreation.GetAvailableClasses().ToList();
                _creationStage = 2;
                CreationPrompt = $"第 2/3 步 · 选择职业（进阶职业需在冒险中转职）";
                Sync(CreationChoices, _creationClasses.Select(c => c.Name));
                Narrate($"血脉已定：{_pickedRace.Name}。", NarrationKind.Narration);
                Narrate("选择你的职业（输入编号或名称）：", NarrationKind.System);
                for (var i = 0; i < _creationClasses.Count; i++)
                    Narrate($"  {i + 1}. {_creationClasses[i].Name}", NarrationKind.System);
                break;
            case 2:
                _pickedClass = PickOne(_creationClasses, input, n => n.Name, n => n.Id);
                if (_pickedClass == null) { Narrate("请输入列表中的编号或名称。", NarrationKind.System); return; }
                _creationTraits = World.CharacterCreation.GetAvailableTraits(_pickedRace.Data, _pickedClass.Data).ToList();
                _creationStage = 3;
                CreationPrompt = "第 3/3 步 · 选择出身特质";
                Sync(CreationChoices, _creationTraits.Select(t => t.Name));
                Narrate($"道路已选：{_pickedClass.Name}。", NarrationKind.Narration);
                Narrate("最后，选择你的出身特质（输入编号或名称）：", NarrationKind.System);
                for (var i = 0; i < _creationTraits.Count; i++)
                    Narrate($"  {i + 1}. {_creationTraits[i].Name} —— {_creationTraits[i].Description}", NarrationKind.System);
                break;
            case 3:
                var trait = PickOne(_creationTraits, input, t => t.Name, t => t.Id);
                if (trait == null) { Narrate("请输入列表中的编号或名称。", NarrationKind.System); return; }
                FinishCreation(trait);
                break;
        }
    }

    /// <summary>取消建档：重置创建状态机的全部中间状态。</summary>
    private void ResetCreation()
    {
        _creationStage = 0;
        _pickedRace = null;
        _pickedClass = null;
        _creationRaces.Clear();
        _creationClasses.Clear();
        _creationTraits.Clear();
        IsCreating = false;
        CreationPrompt = "";
        CreationChoices.Clear();
        Narrate("已取消建档，命运的织机停了下来。", NarrationKind.System);
    }

    private static T PickOne<T>(IReadOnlyList<T> list, string input, Func<T, string> name, Func<T, string> id) where T : class
    {
        if (int.TryParse(input, out var idx) && idx >= 1 && idx <= list.Count) return list[idx - 1];
        return list.FirstOrDefault(x => name(x) == input || id(x) == input)
            ?? list.FirstOrDefault(x => name(x).Contains(input) || id(x).Contains(input));
    }

    private void FinishCreation(TraitDef trait)
    {
        _creationStage = 0;
        IsCreating = false;
        CreationPrompt = "";
        CreationChoices.Clear();
        NarrationLines.Clear();
        _player = World.CharacterCreation.Create(_pickedRace.Id, _pickedClass.Id, trait.Id, _creationName);
        _player.WorldPos = new MapPos(25, 25);
        _equipment = World.CreateEquipment(_player);
        _crafting = new CraftingSystem(_player, World.EventBus);
        _home = new HomeBase(new MapPos(25, 25), 8, 8, World.EventBus, _player);
        _cooldowns = new CooldownManager(_player, World.EventBus);
        _npcInstances.Clear();
        _companions.Clear();
        _skillTree = _pickedClass.SkillTreeRoot != null
            && SkillTreeRegistry.Instance.TryGet(_pickedClass.SkillTreeRoot, out var tree) ? tree : null;
        _skillTree?.UnlockRoots();

        Narrate("══════════════════════════════════════", NarrationKind.System);
        Narrate($"{_player.Name} —— {_pickedRace.Name} / {_pickedClass.Name} / {trait.Name}", NarrationKind.System);
        Narrate("══════════════════════════════════════", NarrationKind.System);
        Narrate("你在村口的老橡树下醒来，行囊里是几份干粮、几瓶药水，和一百枚叮当作响的金币。", NarrationKind.Narration);
        Narrate($"{trait.Description}。从今天起，这片大陆的故事由你书写。", NarrationKind.Narration);
        Narrate("提示：点「交谈 村长罗万」了解村庄近况，点「查看布告板」接取委托。", NarrationKind.System);
        Narrate("村子里太平无事——没有怪物，也没有采集点。向东南西北走出村庄，可用的行动会随你的坐标变化；不知道去哪就点「传闻」。", NarrationKind.System);
        IsPlayerReady = true;
        World.Map.CurrentFog().Update(_player);
        RefreshPanels();
        RefreshOptions();
        CurrentPage = "Game";
        // 建档完成自动存档到所选存档位
        try
        {
            World.SaveManager.Save(_selectedSlot, CaptureCurrent());
            RefreshSlotsInfo();
            RefreshSlotTexts();
        }
        catch (Exception ex) { Narrate("自动存档失败：" + ex.Message, NarrationKind.Error); }
    }

    private bool EnsurePlayer()
    {
        if (_player != null) return true;
        Narrate("你还没有角色。输入「创建 你的名字」开始建档。", NarrationKind.System);
        return false;
    }

    // ==================== 探索 ====================
    private void Move(string dir)
    {
        if (!EnsurePlayer()) return;
        if (dir is not ("东" or "南" or "西" or "北"))
        {
            Narrate("只认得东南西北。", NarrationKind.System);
            return;
        }
        var (dx, dy) = dir switch
        {
            "东" => (1, 0), "西" => (-1, 0), "北" => (0, -1), "南" => (0, 1),
            _ => (0, 0)
        };
        var x = Math.Clamp(_player.WorldPos.X + dx, 0, World.Map.Width - 1);
        var y = Math.Clamp(_player.WorldPos.Y + dy, 0, World.Map.Height - 1);
        if (x == _player.WorldPos.X && y == _player.WorldPos.Y)
        {
            Narrate("前方已是大陆尽头，无路可走。", NarrationKind.Narration);
            return;
        }
        _player.WorldPos = new MapPos(x, y);
        World.SurvivalManager.OnPlayerMove(_player, World.Map);
        foreach (var gp in _gatherPoints.Values) gp.TickCooldown();   // 每走一步 = 一回合，采集点冷却递减
        World.Map.CurrentFog().DecayAfterMove(_player);
        World.Map.CurrentFog().Update(_player);
        Narrate($"你向{dir}走去，脚步落在（{x},{y}）的土地上。", NarrationKind.Narration);
        DescribeSurroundings();
        RefreshNearbyOptions();
    }

    private void CmdLook()
    {
        if (!EnsurePlayer()) return;
        Narrate($"你站在（{_player.WorldPos.X},{_player.WorldPos.Y}）环顾四周。", NarrationKind.Narration);
        DescribeSurroundings();
        Narrate($"饱食 {_player.Survival.Hunger}/100，水分 {_player.Survival.Thirst}/100，体温 {_player.Survival.Temperature}/100。", NarrationKind.System);
    }

    private void DescribeSurroundings()
    {
        var fog = World.Map.CurrentFog();
        var visible = 0;
        for (var y = 0; y < World.Map.Height; y++)
            for (var x = 0; x < World.Map.Width; x++)
                if (fog.IsVisible(x, y)) visible++;
        Narrate($"风拨开迷雾，你已探明 {visible} 处地域。", NarrationKind.System);

        var nearby = World.Map.FindNearby(_player.WorldPos, NearbyRange);
        var monsters = nearby.Where(f => f.Type == FeatureType.MonsterSpawn).Select(f => f.Id).Distinct()
            .Select(id => MonsterTemplateRegistry.Instance.TryGet(id, out var t) ? t.Name : null)
            .Where(n => n != null).ToList();
        var resources = nearby.Where(f => f.Type == FeatureType.GatherPoint).Select(f => f.Id).Distinct()
            .Select(id => ResourceRegistry.Instance.TryGet(id, out var r) ? r.Name : null)
            .Where(n => n != null).ToList();
        var people = World.Map.FindNearby(_player.WorldPos, NpcRange)
            .Where(f => f.Type == FeatureType.NpcSpawn).Select(f => f.Id).Distinct()
            .Select(id => NpcRegistry.Instance.GetAll().FirstOrDefault(n => n.Id == id)?.Name)
            .Where(n => n != null).ToList();
        Narrate(monsters.Count > 0 ? "附近游荡着：" + string.Join("、", monsters) + "。" : "附近没有怪物的踪迹。", NarrationKind.System);
        Narrate(resources.Count > 0 ? "附近可采集：" + string.Join("、", resources) + "。" : "附近没什么可采集的。", NarrationKind.System);
        if (people.Count > 0) Narrate("附近的人：" + string.Join("、", people) + "。", NarrationKind.System);
        if (monsters.Count == 0 && resources.Count == 0) Narrate("点「传闻」可以探听最近目标的方向。", NarrationKind.System);
    }

    private void CmdEat(string arg)
    {
        if (!EnsurePlayer()) return;
        var food = _player.Inventory.Stacks
            .Where(s => s.Def.Type == ItemType.Consumable && s.Def.Nutrition > 0)
            .Select(s => s.Def)
            .FirstOrDefault(d => arg.Length == 0 || d.Name.Contains(arg) || d.Id.Contains(arg));
        if (food == null) { Narrate(arg.Length == 0 ? "行囊里没有能填肚子的东西。" : $"行囊里没有「{arg}」。", NarrationKind.System); return; }
        _player.Inventory.Remove(food.Id, 1);
        _player.Survival.Consume(food);
        Narrate($"你吃下【{food.Name}】，饥意稍缓。", NarrationKind.Narration);
    }

    private void CmdDrink()
    {
        if (!EnsurePlayer()) return;
        var waterNearby = World.Map.FindNearby(_player.WorldPos, NearbyRange)
            .Any(f => f.Type == FeatureType.GatherPoint
                && ResourceRegistry.Instance.TryGet(f.Id, out var r) && r.Category == ResourceCategory.Water);
        if (!waterNearby)
        {
            Narrate("附近没有水源——村里有水井，野外能找到清泉，点「传闻」看看方向。", NarrationKind.System);
            return;
        }
        _player.Survival.Drink(30);
        Narrate("你掬起清水饮下，喉咙里的干渴平息了。", NarrationKind.Narration);
    }

    private void CmdGather(string arg)
    {
        if (!EnsurePlayer()) return;
        var nearbyIds = World.Map.FindNearby(_player.WorldPos, NearbyRange)
            .Where(f => f.Type == FeatureType.GatherPoint).Select(f => f.Id).Distinct().ToList();
        var nearby = ResourceRegistry.Instance.GetAll().Where(r => nearbyIds.Contains(r.Id)).ToList();
        if (arg.Length == 0)
        {
            Narrate(nearby.Count > 0
                ? "附近可采集的资源：" + string.Join("、", nearby.Select(r => r.Name))
                : "这附近没什么可采集的，换个地方看看。", NarrationKind.System);
            return;
        }
        var resource = nearby.FirstOrDefault(r => r.Name.Contains(arg) || r.Id.Contains(arg));
        if (resource == null)
        {
            Narrate($"这附近找不到【{arg}】——它长在别处，先走过去再说。", NarrationKind.System);
            return;
        }
        if (!_gatherPoints.TryGetValue(resource.Id, out var point) || point.IsDepleted)
            _gatherPoints[resource.Id] = point = new GatherPoint(resource);
        var got = point.Gather(_player);
        if (got != null)
        {
            _player.Inventory.Add(got.Value.ItemId, got.Value.Count);
            Narrate($"你俯身劳作，从【{resource.Name}】收获了 {ItemRegistry.Instance.Get(got.Value.ItemId).Name} x{got.Value.Count}。", NarrationKind.Reward);
        }
        else if (point.CooldownRemaining > 0)
        {
            Narrate($"【{resource.Name}】还没缓过来（冷却 {point.CooldownRemaining} 步），稍后再试。", NarrationKind.System);
        }
        else
        {
            Narrate($"你尝试采集【{resource.Name}】，但技有不逮——需要更专业的本事（标签门槛）。", NarrationKind.System);
        }
    }

    // ==================== 战斗 ====================
    private void CmdFight(string arg)
    {
        if (!EnsurePlayer()) return;
        var all = MonsterTemplateRegistry.Instance.GetAll().ToList();
        if (arg.Length == 0)
        {
            Narrate("可以挑战的对手：" + string.Join("、", all.Select(m => m.Name)), NarrationKind.System);
            return;
        }
        var template = all.FirstOrDefault(m => m.Name.Contains(arg) || m.Id.Contains(arg));
        if (template == null) { Narrate($"没有听说过叫「{arg}」的怪物。", NarrationKind.System); return; }
        if (!IsNearby(FeatureType.MonsterSpawn, template.Id, NearbyRange))
        {
            Narrate($"【{template.Name}】不在你附近（{NearbyRange} 步以内）——它在大陆的其他角落，先循着传闻走过去。", NarrationKind.System);
            return;
        }
        if (_player.IsDead) _player.Revive(0.5f);

        Narrate($"你握紧武器，迎面走向【{template.Name}】。战斗开始！", NarrationKind.Combat);
        var enemy = World.SpawnMonster(template.Id);
        var grid = new GridSystem();
        grid.PlaceUnit(_player, new GridPosition(1, 2, Side.Ally));
        grid.PlaceUnit(enemy, new GridPosition(1, 2, Side.Enemy));
        var rng = new Random();
        var battle = new BattleManager(grid, World.EventBus,
            new BattleAI(new BehaviorEngine(rng), rng), World.EffectEngine,
            new List<Unit> { _player }, new List<Unit> { enemy });
        battle.SkillManagers[_player] = _cooldowns;
        var result = battle.RunToCompletion();

        Narrate($"鏖战 {battle.TurnNumber} 回合——", NarrationKind.Combat);
        if (result.PlayerWin)
        {
            Narrate($"【{enemy.Name}】倒下了。你赢得了胜利！", NarrationKind.Combat);
            Narrate($"获得经验，当前经验 {_player.Exp}；剩余 HP {_player.Stats.Hp:F0}/{_player.Stats.MaxHp:F0}。", NarrationKind.Reward);
        }
        else
        {
            Narrate($"你不敌【{enemy.Name}】，倒在了血泊中……", NarrationKind.Combat);
            if (_player.IsDead)
            {
                var outcome = new DeathHandler(DeathMode.Penalty, World.SaveManager, 0).HandleDeath(_player);
                Narrate($"死亡处理：{outcome}", NarrationKind.System);
            }
        }
        _player.RecalculateTags();
    }

    // ==================== 对话 ====================
    private Unit GetNpcInstance(string id)
        => _npcInstances.TryGetValue(id, out var u) ? u : _npcInstances[id] = World.SpawnNpc(id);

    private void CmdTalk(string arg)
    {
        if (!EnsurePlayer()) return;
        var all = NpcRegistry.Instance.GetAll().ToList();
        Unit npc;
        if (arg.Length == 0)
        {
            if (_lastTalkNpcId == null)
            {
                Narrate("村里的人：" + string.Join("、", all.Select(n => n.Name)) + "。想和谁谈谈？", NarrationKind.System);
                return;
            }
            npc = GetNpcInstance(_lastTalkNpcId);
        }
        else
        {
            var def = all.FirstOrDefault(n => n.Name.Contains(arg) || n.Id.Contains(arg));
            if (def == null) { Narrate($"没找到叫「{arg}」的人。", NarrationKind.System); return; }
            npc = GetNpcInstance(def.Id);
        }
        _lastTalkNpcId = npc.Id;
        if (!IsNearby(FeatureType.NpcSpawn, npc.Id, NpcRange))
        {
            Narrate($"{npc.Name}不在你身边——村里的人都在新手村（{World.Map.Width / 2},{World.Map.Height / 2}）附近。", NarrationKind.System);
            return;
        }

        var dialogue = NPCFactory.GetDialogueTree(npc);
        if (dialogue == null) { Narrate($"【{npc.Name}】沉默不语。", NarrationKind.Dialogue); return; }

        Narrate($"你走向{npc.Name}。", NarrationKind.Narration);
        var evalCtx = new EvalContext(_player.ActiveTagIds, new Dictionary<string, object>(), _player, npc);
        var node = dialogue.GetRoot();
        while (node != null)
        {
            Narrate($"{npc.Name}：{node.Text}", NarrationKind.Dialogue);
            foreach (var act in node.OnEnterActions) act.Execute(_player, npc);
            if (node.IsExit) break;
            node = dialogue.Next(node, evalCtx);
        }
    }

    // ==================== AI 自由对话 ====================
    private async Task AiFreeTalkAsync(string utterance)
    {
        if (!EnsurePlayer()) return;
        if (IsAiBusy) { Narrate("（对方还在思索中……稍候再试）", NarrationKind.System); return; }
        var npcId = _lastTalkNpcId ?? NpcRegistry.Instance.GetAll().First().Id;
        var npc = GetNpcInstance(npcId);

        // UI 线程抓取只读快照，后台推理不再触碰可变游戏状态
        var npcName = npc.Name;
        var npcTags = npc.ActiveTagIds.ToArray();
        var npcAffinity = npc.Affinity;
        var playerName = _player.Name;
        var playerTags = _player.ActiveTagIds.ToArray();

        IsAiBusy = true;
        Narrate($"（{npcName}正在回应……本地模型推理中，首次需加载权重）", NarrationKind.System);
        try
        {
            var ai = new NpcDialogueAi(World.Ai.Chat);
            string line = null;
            bool available = false;
            await Task.Run(() =>
            {
                available = ai.IsAvailable;
                if (available)
                    line = ai.GenerateLine(npcName, npcTags, npcAffinity, playerName, playerTags, $"玩家对你说：{utterance}");
            });
            if (!available)
            {
                Narrate($"{npcName}：（模型不可用，回退）嗯……冒险者，愿你一路平安。", NarrationKind.Dialogue);
                Narrate("提示：本地生成模型未就绪。可将 qwen2-7b-q4.gguf 放入 C:\\GameModels（纯ASCII路径）。", NarrationKind.System);
                return;
            }
            Narrate($"{npcName}：{(line ?? "（AI生成失败，回退）你好，冒险者。")}", NarrationKind.Dialogue);
        }
        catch (Exception ex)
        {
            Narrate("AI 调用异常：" + ex.Message, NarrationKind.Error);
        }
        finally { IsAiBusy = false; }
    }

    // ==================== 任务 ====================
    private void CmdQuestList()
    {
        if (!EnsurePlayer()) return;
        if (!IsNearby(FeatureType.Building, "布告板", NearbyRange))
        {
            Narrate("布告板立在新手村的村口——先回村再说（点「传闻」可知方向）。", NarrationKind.System);
            return;
        }
        _questCache = _guild.GetAvailableQuests(_player).ToList();
        if (_questCache.Count == 0)
        {
            Narrate("布告板上暂时没有你能接的委托（任务链由标签推进）。", NarrationKind.System);
            return;
        }
        Narrate($"公会布告板上钉着 {_questCache.Count} 份委托：", NarrationKind.System);
        foreach (var q in _questCache)
            Narrate($"  【{q.Name}】{q.Data.Description}（{q.Data.MinLevel}级起）", NarrationKind.System);
        Narrate("输入「完成 任务名」直接结算委托。", NarrationKind.System);
    }

    private void CmdQuestComplete(string arg)
    {
        if (!EnsurePlayer()) return;
        if (_questCache.Count == 0) _questCache = _guild.GetAvailableQuests(_player).ToList();
        var quest = _questCache.FirstOrDefault(q => q.Name.Contains(arg) || q.Id.Contains(arg));
        if (quest == null) { Narrate($"没有可完成的委托叫「{arg}」。先用「任务」查看列表。", NarrationKind.System); return; }

        var gains = new List<string>();
        if (quest.Data.Rewards != null)
        {
            foreach (var (key, value) in quest.Data.Rewards)
            {
                if (key == "gold") { _player.Gold += value; gains.Add($"金币 +{value}"); }
                else { _player.Inventory.Add(key, value); gains.Add($"{ItemRegistry.Instance.Get(key).Name} x{value}"); }
            }
        }
        if (quest.Data.RewardTagIds != null)
            foreach (var tagId in quest.Data.RewardTagIds)
            {
                _player.QuestTagIds.Add(tagId);
                gains.Add($"获得标签[{tagId}]");
            }
        _player.RecalculateTags();
        World.EventBus.Emit(EventTypes.QuestCompleted, quest.Id, _player);
        Narrate($"委托【{quest.Name}】完成！", NarrationKind.Reward);
        if (gains.Count > 0) Narrate("报酬：" + string.Join("，", gains), NarrationKind.Reward);
        _questCache = _guild.GetAvailableQuests(_player).ToList();
    }

    // ==================== 成长：技能 / 制造 / 家园 ====================
    private void CmdSkills()
    {
        if (!EnsurePlayer()) return;
        if (_skillTree == null) { Narrate("当前职业没有技能树。", NarrationKind.System); return; }
        var unlocked = _skillTree.UnlockedSkillIds;
        Narrate("技能树：" + string.Join("，", _skillTree.Graph.AllNodes.Select(n => n.Data.Skill)
            .Select(s => $"{s.Name}{(unlocked.Contains(s.Id) ? "(已解锁)" : "(未解锁)")}")), NarrationKind.System);
    }

    private void CmdUnlockSkills()
    {
        if (!EnsurePlayer() || _skillTree == null) return;
        var roots = _skillTree.UnlockRoots();
        var extra = _skillTree.CheckUnlocks(_player.ActiveTagIds);
        if (roots.Count == 0 && extra.Count == 0) { Narrate("没有新的技能可以解锁。", NarrationKind.System); return; }
        Narrate("技能解锁：" + string.Join("、", roots.Concat(extra).Select(s => s.Name)), NarrationKind.Reward);
    }

    private void CmdRecipes()
    {
        if (!EnsurePlayer()) return;
        _recipeCache = _crafting.GetKnownRecipes().ToList();
        if (_recipeCache.Count == 0) { Narrate("你还不会任何配方（学习锻造/炼金/烹饪等标签以解锁）。", NarrationKind.System); return; }
        Narrate("已掌握的配方：" + string.Join("、", _recipeCache.Select(r => r.Name)), NarrationKind.System);
    }

    private void CmdCraft(string arg)
    {
        if (!EnsurePlayer()) return;
        if (_recipeCache.Count == 0) _recipeCache = _crafting.GetKnownRecipes().ToList();
        if (arg.Length == 0) { CmdRecipes(); return; }
        var recipe = _recipeCache.FirstOrDefault(r => r.Name.Contains(arg) || r.Id.Contains(arg));
        if (recipe == null) { Narrate($"你还没学会「{arg}」的配方。", NarrationKind.System); return; }
        if (!NearHome())
        {
            Narrate("制造需要家园里的工作台——先回家园再动手（点「传闻」可知方向）。", NarrationKind.System);
            return;
        }
        var result = _crafting.Craft(recipe, _player.Inventory);
        Narrate(result.Success
            ? $"炉火与锤声之后，【{recipe.Name}】完成了。"
            : $"制造【{recipe.Name}】失败：{result.Error}",
            result.Success ? NarrationKind.Reward : NarrationKind.System);
    }

    private void CmdBuild(string arg)
    {
        if (!EnsurePlayer()) return;
        var ids = BuildingRegistry.Instance.GetAllIds().ToList();
        if (arg.Length == 0)
        {
            Narrate("可建造的建筑（用英文编号建造，如：建造 farm）：" + string.Join("、", ids), NarrationKind.System);
            return;
        }
        if (!NearHome())
        {
            Narrate("建造只能在家园范围内进行——先回家园（点「传闻」可知方向）。", NarrationKind.System);
            return;
        }
        var id = ids.FirstOrDefault(i => i.Contains(arg, StringComparison.OrdinalIgnoreCase));
        if (id == null) { Narrate($"没有叫「{arg}」的建筑图纸。", NarrationKind.System); return; }
        if (!BuildingRegistry.Instance.TryCreate(id, out var building)) return;
        var placed = _home.PlaceBuilding(building, _buildX, _buildY);
        Narrate(placed
            ? $"【{building.Name}】在（{_buildX},{_buildY}）破土动工并落成了。"
            : $"建造【{building.Name}】失败——材料或前置建筑不足。",
            placed ? NarrationKind.Reward : NarrationKind.System);
        if (placed)
        {
            _buildX++;
            if (_buildX > 6) { _buildX = 1; _buildY++; if (_buildY > 6) _buildY = 1; }
        }
    }

    private void CmdHome()
    {
        if (!EnsurePlayer()) return;
        if (!NearHome())
        {
            Narrate($"你不在家园——家园在{DirectionTo(_home.Position)}。", NarrationKind.System);
            return;
        }
        var buildings = _home.GetBuildings();
        Narrate($"家园等级 Lv{_home.Level}。已建建筑：{(buildings.Count == 0 ? "（空）" : string.Join("、", buildings.Select(b => b.Name)))}", NarrationKind.System);
    }

    private void CmdHomeLevelUp()
    {
        if (!EnsurePlayer()) return;
        if (!NearHome())
        {
            Narrate("扩建家园得站在自家地基上——先回家园。", NarrationKind.System);
            return;
        }
        _home.LevelUp();
        Narrate($"家园扩建完成，升至 Lv{_home.Level}。", NarrationKind.Reward);
    }

    // ==================== 社交 ====================
    private void CmdRecruit(string arg)
    {
        if (!EnsurePlayer()) return;
        var all = NpcRegistry.Instance.GetAll().ToList();
        var def = all.FirstOrDefault(n => n.Name.Contains(arg) || n.Id.Contains(arg));
        if (def == null) { Narrate($"你想招募谁？村里的人：{string.Join("、", all.Select(n => n.Name))}", NarrationKind.System); return; }
        var npc = GetNpcInstance(def.Id);
        if (!IsNearby(FeatureType.NpcSpawn, npc.Id, NpcRange))
        {
            Narrate($"{npc.Name}不在你身边——先回到新手村再谈招募。", NarrationKind.System);
            return;
        }
        if (!npc.Metadata.ContainsKey("recruitmentDef"))
            npc.Metadata["recruitmentDef"] = new RecruitmentDef(new AlwaysTrue(), 60, true, 500, 10);
        var attempt = RecruitmentSystem.RecruitByBond(_player, npc);
        Narrate($"招募{npc.Name}：{attempt.Message}", attempt.Success ? NarrationKind.Reward : NarrationKind.System);
        if (attempt.Success && !_companions.Contains(npc)) _companions.Add(npc);
    }

    // ==================== 角色面板指令 ====================
    private void CmdStatus()
    {
        if (!EnsurePlayer()) return;
        Narrate($"{_player.Name}　Lv{_player.Level}　{_player.CurrentRace?.Name} / {_player.CurrentClass?.Name}", NarrationKind.System);
        Narrate($"HP {_player.Stats.Hp:F0}/{_player.Stats.MaxHp:F0}　经验 {_player.Exp}　金币 {_player.Gold}", NarrationKind.System);
        Narrate($"攻击 {_player.GetStat("ATK"):F0}　防御 {_player.GetStat("DEF"):F0}　速度 {_player.GetStat("SPD"):F0}　暴击 {_player.GetStat("CRIT"):F2}", NarrationKind.System);
        Narrate($"饱食 {_player.Survival.Hunger}　水分 {_player.Survival.Thirst}　体温 {_player.Survival.Temperature}　坐标（{_player.WorldPos.X},{_player.WorldPos.Y}）", NarrationKind.System);
    }

    private void CmdInventory()
    {
        if (!EnsurePlayer()) return;
        Narrate(_player.Inventory.Stacks.Count == 0
            ? "行囊空空如也。"
            : "行囊：" + string.Join("，", _player.Inventory.Stacks.Select(s => $"{s.Def.Name}x{s.Count}")), NarrationKind.System);
    }

    private void CmdTags()
    {
        if (!EnsurePlayer()) return;
        Narrate(_player.ActiveTagIds.Count == 0
            ? "你身上没有任何标签。"
            : "当前标签（驱动一切）：" + string.Join("、", _player.ActiveTagIds), NarrationKind.System);
    }

    private void CmdEquip(string arg)
    {
        if (!EnsurePlayer()) return;
        if (arg.Length == 0)
        {
            var equipped = _equipment.GetAllEquipped();
            Narrate(equipped.Count == 0
                ? "你什么都没装备。可装备的兵器甲胄：" + string.Join("、", EquipRegistry.Instance.GetAll().Select(e => e.Name))
                : "当前装备：\n" + string.Join("\n", equipped.Values.Select(e => $"  [{e.Slot}] {e.Name}（耐久 {e.CurrentDurability}/{e.MaxDurability}）")), NarrationKind.System);
            return;
        }
        var def = EquipRegistry.Instance.GetAll().FirstOrDefault(e => e.Name.Contains(arg) || e.Id.Contains(arg));
        if (def == null) { Narrate($"没有叫「{arg}」的装备。", NarrationKind.System); return; }
        var result = _equipment.Equip(new Equip(def));
        Narrate(result.Success ? $"你装备了【{def.Name}】。" : $"无法装备【{def.Name}】：{result.Error}",
            result.Success ? NarrationKind.Reward : NarrationKind.System);
    }

    // ==================== 存档 ====================
    private SaveData CaptureCurrent() => SaveManager.Capture(_player, World.Map, _home, _companions, _equipment,
        _npcInstances.ToDictionary(kv => kv.Key, kv => kv.Value.Affinity), _reputation.ToSaveMap(),
        new Dictionary<string, QuestSaveData>(), new Dictionary<string, bool>(), 0, "新手村");

    private void CmdSave()
    {
        if (!EnsurePlayer()) return;
        World.SaveManager.Save(_selectedSlot, CaptureCurrent());
        Narrate($"你把旅途的足迹封存在水晶里（存档位 {_selectedSlot}）。", NarrationKind.System);
        RefreshSlotsInfo();
        RefreshSlotTexts();
    }

    private void CmdLoad() { if (DoLoad(1)) CurrentPage = "Game"; }

    /// <summary>读档核心：恢复全部状态（家园/迷雾/好感/声望）。成功返回 true。</summary>
    private bool DoLoad(int slot)
    {
        try
        {
            if (World.SaveManager.Load(slot) == null) { Narrate($"存档位 {slot} 是空的。", NarrationKind.System); return false; }
            var state = World.GameLoader.Load(slot);
            _selectedSlot = slot;
            NarrationLines.Clear();
            _player = state.Player;
            _equipment = state.Equipment;
            _companions.Clear();
            _companions.AddRange(state.Companions);
            _crafting = new CraftingSystem(_player, World.EventBus);
            _home = state.Home ?? new HomeBase(new MapPos(25, 25), 8, 8, World.EventBus, _player);
            _cooldowns = new CooldownManager(_player, World.EventBus);
            // 恢复地图迷雾与层级
            World.Map.SwitchLayer(state.Map.CurrentLayer);
            World.Map.CurrentFog().ImportRows(state.Map.CurrentFog().ExportRows());
            // 恢复 NPC 好感度（清掉旧实例，避免残留）
            _npcInstances.Clear();
            _lastTalkNpcId = null;
            foreach (var kv in state.NpcAffinities) GetNpcInstance(kv.Key).Affinity = kv.Value;
            // 恢复声望
            _reputation.LoadFrom(state.FactionReputations);
            var classNode = _player.CurrentClass;
            _skillTree = classNode?.SkillTreeRoot != null
                && SkillTreeRegistry.Instance.TryGet(classNode.SkillTreeRoot, out var tree) ? tree : null;
            Narrate($"记忆涌回——{_player.Name}，Lv{_player.Level}，金币 {_player.Gold}。家园与探索足迹已恢复。旅途继续。", NarrationKind.System);
            IsPlayerReady = true;
            RefreshPanels();
            RefreshOptions();
            return true;
        }
        catch (Exception ex)
        {
            Narrate("读档失败：" + ex.Message, NarrationKind.Error);
            return false;
        }
    }

    private void CmdSlots()
    {
        var slots = World.SaveManager.ListSlots().ToList();
        Narrate(slots.Count == 0 ? "尚无存档。" : string.Join("\n", slots.Select(s => $"存档位 {s.Slot} —— {s.Location}")), NarrationKind.System);
    }

    private void RefreshSlotsInfo()
    {
        if (World == null) return;
        var slots = World.SaveManager.ListSlots().ToList();
        SlotsText = slots.Count == 0 ? "（无）" : string.Join("\n", slots.Select(s => $"位{s.Slot} {s.Location}"));
    }

    // ==================== 面板文本 ====================
    private string _playerInfoText = "尚未创建角色";
    public string PlayerInfoText { get => _playerInfoText; private set => Set(ref _playerInfoText, value); }

    private string _statsText = "";
    public string StatsText { get => _statsText; private set => Set(ref _statsText, value); }

    private string _survivalText = "";
    public string SurvivalText { get => _survivalText; private set => Set(ref _survivalText, value); }

    private string _tagsText = "";
    public string TagsText { get => _tagsText; private set => Set(ref _tagsText, value); }

    private string _inventoryText = "";
    public string InventoryText { get => _inventoryText; private set => Set(ref _inventoryText, value); }

    private string _equipSlotsText = "";
    public string EquipSlotsText { get => _equipSlotsText; private set => Set(ref _equipSlotsText, value); }

    private string _locationText = "";
    public string LocationText { get => _locationText; private set => Set(ref _locationText, value); }

    private string _questText = "";
    public string QuestText { get => _questText; private set => Set(ref _questText, value); }

    private string _npcText = "";
    public string NpcText { get => _npcText; private set => Set(ref _npcText, value); }

    private string _skillText = "";
    public string SkillText { get => _skillText; private set => Set(ref _skillText, value); }

    private string _homeText = "";
    public string HomeText { get => _homeText; private set => Set(ref _homeText, value); }

    private string _aiStatusText = "";
    public string AiStatusText { get => _aiStatusText; private set => Set(ref _aiStatusText, value); }

    private string _slotsText = "";
    public string SlotsText { get => _slotsText; private set => Set(ref _slotsText, value); }

    private void RefreshPanels()
    {
        if (_player == null) return;

        PlayerInfoText = $"{_player.Name}\nLv{_player.Level}　{_player.CurrentRace?.Name} / {_player.CurrentClass?.Name}";
        StatsText = $"HP　{_player.Stats.Hp:F0}/{_player.Stats.MaxHp:F0}\n攻击　{_player.GetStat("ATK"):F0}\n防御　{_player.GetStat("DEF"):F0}\n速度　{_player.GetStat("SPD"):F0}\n暴击　{_player.GetStat("CRIT"):F2}\n经验　{_player.Exp}\n金币　{_player.Gold}";
        SurvivalText = $"饱食　{_player.Survival.Hunger}/100\n水分　{_player.Survival.Thirst}/100\n体温　{_player.Survival.Temperature}/100";
        TagsText = _player.ActiveTagIds.Count == 0 ? "（无）" : string.Join("\n", _player.ActiveTagIds.Select(t => $"[{t}]"));
        InventoryText = _player.Inventory.Stacks.Count == 0
            ? "（空）"
            : string.Join("\n", _player.Inventory.Stacks.Select(s => $"{s.Def.Name} x{s.Count}"));
        EquipSlotsText = _equipment == null || _equipment.GetAllEquipped().Count == 0
            ? "（未装备）"
            : string.Join("\n", _equipment.GetAllEquipped().Values.Select(e => $"{e.Name}（耐久{e.CurrentDurability}/{e.MaxDurability}）"));
        LocationText = $"坐标（{_player.WorldPos.X},{_player.WorldPos.Y}）";

        _questCache = _guild.GetAvailableQuests(_player).ToList();
        QuestText = _questCache.Count == 0 ? "（无可接委托）" : string.Join("\n", _questCache.Select(q => $"【{q.Name}】"));

        var sb = new StringBuilder();
        foreach (var kv in _npcInstances)
        {
            var npc = kv.Value;
            var bond = new BondSystem(_player, npc);
            sb.AppendLine($"{npc.Name}　好感{npc.Affinity}　羁绊Lv{bond.CurrentLevel()}");
        }
        if (_companions.Count > 0) sb.AppendLine("队友：" + string.Join("、", _companions.Select(c => c.Name)));
        NpcText = sb.Length == 0 ? "（尚未结识任何人）" : sb.ToString().TrimEnd();

        SkillText = _skillTree == null ? "（无技能树）"
            : string.Join("\n", _skillTree.Graph.AllNodes.Select(n => n.Data.Skill)
                .Select(s => $"{s.Name}{(_skillTree.UnlockedSkillIds.Contains(s.Id) ? " ·已解锁" : "")}"));

        HomeText = _home == null ? "" : $"家园 Lv{_home.Level}\n建筑：{(_home.GetBuildings().Count == 0 ? "（空）" : string.Join("、", _home.GetBuildings().Select(b => b.Name)))}";
        RefreshOptions();
    }
}

using GameCore.Creation;
using GameCore.Effect;
using GameCore.EventBus;
using GameCore.Graph;
using GameCore.Save;
using GameCore.Tag;
using GameCore.World;

namespace GameCore;

/// <summary>
/// GameWorld — 全局上下文组装。统一加载全部 JSON 配置（依赖顺序）→ 构建基础设施 →
/// 提供各系统工厂。Unit 级系统（装备/制造/冷却）按单位创建。
/// 加载铁律：ItemRegistry 必须先于任何 Unit 创建（Inventory 构造时捕获 Instance）。
/// </summary>
public sealed class GameWorld
{
    // ==================== 基础设施 ====================
    public EventBusImpl EventBus { get; } = new();
    public TagConditionParser ConditionParser { get; } = new();
    public EffectParser EffectParser { get; }
    public EffectEngine EffectEngine { get; } = new();
    public TagRegistry TagRegistry { get; } = new();
    public TagFactory TagFactory { get; private set; }

    // ==================== 三图 ====================
    public GraphEngine<RaceData> RaceGraph { get; private set; }
    public GraphEngine<ClassData> ClassGraph { get; private set; }
    public GraphEngine<QuestData> QuestGraph { get; private set; }

    // ==================== 世界 ====================
    public WorldMap Map { get; private set; }
    public SurvivalManager SurvivalManager { get; private set; }

    // ==================== 存档 ====================
    public SaveManager SaveManager { get; private set; }
    public GameLoader GameLoader { get; private set; }

    // ==================== 本地AI ====================
    public AI.AiContext Ai { get; private set; }

    // ==================== 工厂 ====================
    public CharacterCreation CharacterCreation { get; private set; }
    public Monster.MonsterFactory MonsterFactory { get; private set; }
    public Npc.NPCFactory NpcFactory { get; private set; }

    public string DataDir { get; private set; }

    private GameWorld()
    {
        EffectParser = new EffectParser(ConditionParser);
    }

    /// <summary>
    /// 引导装配：加载全部配置 → 构建上下文。
    /// dataDir 缺省 = 输出目录下的 Data/（由 csproj 拷贝）。
    /// </summary>
    public static GameWorld Bootstrap(string dataDir = null, int worldWidth = 50, int worldHeight = 50,
        string saveDir = "saves", Random rng = null, AI.AiSettings aiSettings = null)
    {
        var world = new GameWorld();
        world.DataDir = dataDir ?? Path.Combine(AppContext.BaseDirectory, "Data");
        var d = world.DataDir;

        // 0. 本地AI上下文（离线自动装配 Null 实现，不阻塞启动）
        world.Ai = AI.AiContext.Create(aiSettings ?? AI.AiSettings.Default);

        // 1. 标签契约层（一切条件/效果的基础）
        world.TagRegistry.Load(Path.Combine(d, "tags.json"), world.EffectParser);
        world.TagFactory = new TagFactory(world.TagRegistry);

        // 2. 物品（必须先于 Unit 创建）
        new Item.ItemRegistry().Load(Path.Combine(d, "items.json"));

        // 3. Buff / 装备 / 套装
        new Buff.BuffRegistry().Load(Path.Combine(d, "buffs.json"), world.EffectParser);
        new Equipment.EquipRegistry().Load(Path.Combine(d, "equipments.json"), world.EffectParser, world.ConditionParser);
        new Equipment.SetBonusRegistry().Load(Path.Combine(d, "setBonuses.json"), world.EffectParser);

        // 4. 技能 + 技能树
        var skillRegistry = new Skill.SkillRegistry();
        skillRegistry.Load(Path.Combine(d, "skills.json"), world.EffectParser, world.ConditionParser);
        new Skill.SkillTreeRegistry(world.ConditionParser, skillRegistry).Load(Path.Combine(d, "skillTrees.json"));

        // 5. 三图（种族进化 / 职业转职 / 任务）
        var loader = new GraphLoader(world.ConditionParser, world.TagRegistry);
        world.RaceGraph = loader.LoadRaceGraph(Path.Combine(d, "races.json"));
        world.ClassGraph = loader.LoadClassGraph(Path.Combine(d, "classes.json"));
        world.QuestGraph = loader.LoadQuestGraph(Path.Combine(d, "quests.json"));

        // 6. 世界内容注册表
        new Monster.MonsterTemplateRegistry().Load(Path.Combine(d, "monsters.json"));
        new Craft.ResourceRegistry().Load(Path.Combine(d, "resources.json"));
        new Craft.RecipeRegistry().Load(Path.Combine(d, "recipes.json"));
        new Home.BuildingRegistry().Load(Path.Combine(d, "buildings.json"));
        new Npc.NpcRegistry().Load(Path.Combine(d, "npcs.json"));
        new TraitRegistry().Load(Path.Combine(d, "traits.json"));
        new Social.TagAffinityMatrix().Load(Path.Combine(d, "tag_affinity.json"));
        new Ally.BondSkillRegistry().LoadFromText(File.ReadAllText(Path.Combine(d, "bond_skills.json")));

        // 7. 世界地图 + 生存统筹
        world.Map = new WorldMap(worldWidth, worldHeight);
        world.SurvivalManager = new SurvivalManager(world.EventBus);
        world.PopulateWorld();

        // 8. 存档体系
        world.SaveManager = new SaveManager(saveDir);
        world.GameLoader = new GameLoader(world.SaveManager, world.RaceGraph, world.ClassGraph,
            world.TagFactory, world.EffectEngine, world.EventBus, worldWidth, worldHeight);

        // 9. 工厂
        world.CharacterCreation = new CharacterCreation(world.RaceGraph, world.ClassGraph,
            TraitRegistry.Instance, world.TagFactory, world.EffectEngine, world.EventBus);
        world.MonsterFactory = new Monster.MonsterFactory(world.TagFactory, world.EffectEngine, world.EventBus, rng);
        world.NpcFactory = new Npc.NPCFactory(world.TagFactory, world.EffectEngine, world.EventBus);

        return world;
    }

    // ==================== 运行时装配辅助 ====================

    /// <summary>
    /// 世界布置：把 NPC（新手村）、怪物刷新点、采集点撒到地图上（固定种子，布局稳定）。
    /// 行动选项按玩家坐标查询这些地形物，落地不能打全图的怪。
    /// </summary>
    private void PopulateWorld()
    {
        var rng = new Random(20260804);
        var cx = Map.Width / 2;
        var cy = Map.Height / 2;
        var village = new MapPos(cx, cy);

        // 村庄设施：布告板（接委托）+ 水井（喝水点），村内即可使用
        Map.AddFeature(new TerrainFeature("布告板", ClampPos(cx + 1, cy + 1), FeatureType.Building));
        Map.AddFeature(new TerrainFeature("spring", ClampPos(cx - 1, cy - 1), FeatureType.GatherPoint));

        // 新手村 NPC：村庄半径 2 格内
        var npcIdx = 0;
        foreach (var npc in Npc.NpcRegistry.Instance.GetAll())
        {
            var angle = npcIdx * 2 * Math.PI / Math.Max(1, Npc.NpcRegistry.Instance.GetAll().Count());
            var radius = npcIdx % 3 == 0 ? 1 : 2;
            Map.AddFeature(new TerrainFeature(npc.Id,
                ClampPos(cx + (int)Math.Round(Math.Cos(angle) * radius), cy + (int)Math.Round(Math.Sin(angle) * radius)),
                FeatureType.NpcSpawn));
            npcIdx++;
        }

        // 怪物刷新点：每种怪 2 处，距村 8~22 格；另加 2 处郊外点（距村 6~9 格，村内不可见）
        var templates = Monster.MonsterTemplateRegistry.Instance.GetAll().ToList();
        foreach (var t in templates)
            for (var i = 0; i < 2; i++)
                Map.AddFeature(new TerrainFeature(t.Id, ScatterPos(village, rng, 8, 22), FeatureType.MonsterSpawn));
        for (var i = 0; i < 2 && templates.Count > 0; i++)
            Map.AddFeature(new TerrainFeature(templates[rng.Next(templates.Count)].Id,
                ScatterPos(village, rng, 6, 9), FeatureType.MonsterSpawn));

        // 采集点：每种资源 2 处，距村 6~22 格；另加 1 处郊外点（距村 5~8 格，村内不可见）
        foreach (var r in Craft.ResourceRegistry.Instance.GetAll())
        {
            for (var i = 0; i < 2; i++)
                Map.AddFeature(new TerrainFeature(r.Id, ScatterPos(village, rng, 6, 22), FeatureType.GatherPoint));
            Map.AddFeature(new TerrainFeature(r.Id, ScatterPos(village, rng, 5, 8), FeatureType.GatherPoint));
        }
    }

    /// <summary>以锚点为中心、在 [minDist, maxDist] 环带内随机取一个地图内坐标。</summary>
    private MapPos ScatterPos(MapPos anchor, Random rng, int minDist, int maxDist)
    {
        for (var attempt = 0; attempt < 20; attempt++)
        {
            var angle = rng.NextDouble() * 2 * Math.PI;
            var dist = minDist + rng.NextDouble() * (maxDist - minDist);
            var pos = ClampPos(anchor.X + (int)Math.Round(Math.Cos(angle) * dist),
                anchor.Y + (int)Math.Round(Math.Sin(angle) * dist));
            var d = pos.DistanceTo(anchor);
            if (d >= minDist - 0.5 && d <= maxDist + 0.5) return pos;
        }
        return ClampPos(anchor.X + minDist, anchor.Y);
    }

    private MapPos ClampPos(int x, int y)
        => new(Math.Clamp(x, 0, Map.Width - 1), Math.Clamp(y, 0, Map.Height - 1));

    /// <summary>玩家就绪后绑定自动保存触发器（capture 闭包采集当前状态）。</summary>
    public AutoSaveTrigger BindAutoSave(Func<SaveData> capture)
        => new(EventBus, SaveManager, capture);

    /// <summary>创建玩家专属装备管理器。</summary>
    public Equipment.EquipmentManager CreateEquipment(Unit.Unit owner)
        => new(owner, EventBus);

    /// <summary>从模板生成怪物。</summary>
    public Unit.Unit SpawnMonster(string templateId) => MonsterFactory.Create(templateId);

    /// <summary>从定义生成NPC。</summary>
    public Unit.Unit SpawnNpc(string npcId) => NpcFactory.Create(npcId);
}

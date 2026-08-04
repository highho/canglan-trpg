namespace GameCore.Unit;

/// <summary>Unit 角色偏向：Monster/NPC/Ally/Player — 三者是同一张 Unit 表，只是行为池与关系不同。</summary>
public enum UnitRole { Monster, Npc, Ally, Player }

/// <summary>对玩家的关系状态。</summary>
public enum RelationState { Hostile, Neutral, Friendly, Ally }

/// <summary>战斗模式：切磋不致死 / 打劫可投降 / 袭杀致死。</summary>
public enum CombatMode { None, Spar, Rob, Lethal }

/// <summary>
/// 行为选项定义。
/// TagWeights 结构: category(IDENTITY/PERSONALITY/EMOTION...) → {tagId → weightModifier}。
/// </summary>
public sealed record BehaviorOption(
    string Id,
    string Name,
    int BaseWeight,
    IReadOnlyDictionary<string, Dictionary<string, int>> TagWeights)
{
    public BehaviorOption(string id, string name, int baseWeight)
        : this(id, name, baseWeight, new Dictionary<string, Dictionary<string, int>>()) { }
}

/// <summary>
/// 行为池：一组行为选项。socialPool（社交）/ combatPool（战斗）按角色状态切换激活。
/// </summary>
public sealed class BehaviorPool
{
    public string Id { get; }
    public string Name { get; }
    private readonly List<BehaviorOption> _options = new();

    public BehaviorPool(string id, string name)
    {
        Id = id;
        Name = name;
    }

    public IReadOnlyList<BehaviorOption> Options => _options;

    public BehaviorPool Add(BehaviorOption option)
    {
        _options.Add(option);
        return this;
    }

    public BehaviorOption Find(string optionId)
        => _options.FirstOrDefault(o => o.Id == optionId);
}

/// <summary>预置行为池工厂：战斗四选项（攻击/防御/逃跑/呼叫增援）+ 队友行为（掩护/背刺）。</summary>
public static class BehaviorPools
{
    public static BehaviorPool DefaultCombatPool()
    {
        var pool = new BehaviorPool("combat", "战斗行为池");
        pool.Add(new BehaviorOption("attack", "攻击", 50, new Dictionary<string, Dictionary<string, int>>
        {
            ["PERSONALITY"] = new() { ["勇敢"] = 20, ["懦弱"] = -10 },
            ["EMOTION"] = new() { ["愤怒"] = 30, ["恐惧"] = -30 }
        }));
        pool.Add(new BehaviorOption("defend", "防御", 30, new Dictionary<string, Dictionary<string, int>>
        {
            ["PERSONALITY"] = new() { ["懦弱"] = 20, ["勇敢"] = -10 },
            ["EMOTION"] = new() { ["恐惧"] = 20 }
        }));
        pool.Add(new BehaviorOption("flee", "逃跑", 10, new Dictionary<string, Dictionary<string, int>>
        {
            ["PERSONALITY"] = new() { ["懦弱"] = 40, ["勇敢"] = -30 },
            ["EMOTION"] = new() { ["恐惧"] = 40 }
        }));
        pool.Add(new BehaviorOption("call_help", "呼叫增援", 10, new Dictionary<string, Dictionary<string, int>>
        {
            ["PERSONALITY"] = new() { ["狡猾"] = 20 }
        }));
        return pool;
    }

    public static BehaviorPool DefaultSocialPool()
    {
        var pool = new BehaviorPool("social", "社交行为池");
        pool.Add(new BehaviorOption("talk", "交谈", 50));
        pool.Add(new BehaviorOption("trade", "交易", 30, new Dictionary<string, Dictionary<string, int>>
        {
            ["IDENTITY"] = new() { ["商人"] = 30 },
            ["PERSONALITY"] = new() { ["贪婪"] = 20 }
        }));
        pool.Add(new BehaviorOption("quest", "任务", 30, new Dictionary<string, Dictionary<string, int>>
        {
            ["IDENTITY"] = new() { ["长老"] = 20, ["守卫"] = 10 }
        }));
        pool.Add(new BehaviorOption("accept_bribe", "接受贿赂", 20, new Dictionary<string, Dictionary<string, int>>
        {
            ["PERSONALITY"] = new() { ["正直"] = -40, ["贪婪"] = 30 },
            ["IDENTITY"] = new() { ["守卫"] = -20 },
            ["EMOTION"] = new() { ["愤怒"] = 10 }
        }));
        pool.Add(new BehaviorOption("reject", "拒绝", 40, new Dictionary<string, Dictionary<string, int>>
        {
            ["PERSONALITY"] = new() { ["正直"] = 30 },
            ["IDENTITY"] = new() { ["守卫"] = 20 }
        }));
        pool.Add(new BehaviorOption("report", "举报", 15, new Dictionary<string, Dictionary<string, int>>
        {
            ["PERSONALITY"] = new() { ["正直"] = 20 },
            ["IDENTITY"] = new() { ["守卫"] = 10 }
        }));
        pool.Add(new BehaviorOption("surrender", "投降", 20, new Dictionary<string, Dictionary<string, int>>
        {
            ["PERSONALITY"] = new() { ["懦弱"] = 40, ["勇敢"] = -30 },
            ["EMOTION"] = new() { ["恐惧"] = 40 }
        }));
        pool.Add(new BehaviorOption("fight_back", "反击", 40, new Dictionary<string, Dictionary<string, int>>
        {
            ["PERSONALITY"] = new() { ["勇敢"] = 30, ["懦弱"] = -20 },
            ["EMOTION"] = new() { ["愤怒"] = 30 }
        }));
        return pool;
    }
}

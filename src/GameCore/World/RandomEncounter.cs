using GameCore.EventBus;

namespace GameCore.World;

/// <summary>随机遭遇事件：包含描述文本、选项和奖励。</summary>
public sealed class EncounterEvent
{
    public string Id { get; init; }
    public string Description { get; init; }
    public List<EncounterOption> Options { get; init; } = new();
    public int MinLevel { get; init; } = 1;
    public int MaxLevel { get; init; } = 99;
    public float Weight { get; init; } = 10f;
    /// <summary>遭遇触发后的冷却步数（同类型事件不重复出现）。</summary>
    public int CooldownSteps { get; init; }
}

/// <summary>遭遇选项（VM 渲染为按钮）。</summary>
public sealed class EncounterOption
{
    public string Label { get; init; }        // 按钮文字："上前查看"、"偷偷离开"
    public string ResultText { get; init; }   // 选择后的叙事文本
    public Dictionary<string, int> Rewards { get; init; } = new();   // { gold:50, healing_potion:1 }
    public Dictionary<string, int> Penalties { get; init; } = new(); // { hp:-10 }
    /// <summary>选项需要满足的标签条件（缺省全部可选）。</summary>
    public Tag.ITagCondition Condition { get; init; }
}

/// <summary>随机遭遇表：每次玩家移动时抽取事件。</summary>
public static class EncounterTable
{
    private static readonly List<EncounterEvent> _pool = new();
    private static readonly Dictionary<string, int> _cooldowns = new();   // 事件ID → 剩余冷却步数
    private static readonly Random _rng = new();

    static EncounterTable()
    {
        _pool.Add(new EncounterEvent
        {
            Id = "fallen_trader",
            Description = "路边趴着一个浑身是血的商人，他虚弱地抬起手，喃喃道：「…救命…强盗…」",
            MinLevel = 1, MaxLevel = 99, Weight = 12, CooldownSteps = 15,
            Options =
            {
                new() { Label = "用治疗药水救助他", ResultText = "你用治疗药水清洗了他的伤口。商人感激涕零：「愿幸运女神祝福你！」他塞给你一袋金币后踉跄离开。",
                    Rewards = new() { ["gold"] = 80, ["reputation_merchant"] = 5 } },
                new() { Label = "搜刮他身上的财物", ResultText = "你趁人之危翻遍了他的行囊，拿走了值钱的物件。商人的咒骂声渐渐消失在风中。",
                    Rewards = new() { ["gold"] = 120, ["wolf_pelt"] = 1 }, Penalties = new() { ["hp"] = -5 } },
                new() { Label = "装作没看见，加快脚步离开", ResultText = "你移开视线，加快了脚步。身后的呻吟声很快被风声吞没。" }
            }
        });

        _pool.Add(new EncounterEvent
        {
            Id = "hidden_chest",
            Description = "在一棵枯树的根部，你瞥见一个半掩在泥土里的旧木箱。锁已经锈坏了。",
            MinLevel = 1, MaxLevel = 99, Weight = 10, CooldownSteps = 12,
            Options =
            {
                new() { Label = "打开箱子", ResultText = "你撬开木箱，里面躺着几枚古币和一柄还算锋利的短剑。",
                    Rewards = new() { ["gold"] = 50, ["iron_sword"] = 1 } },
                new() { Label = "小心检查后再开（需[谨慎]特质）", ResultText = "你发现箱子底部压着毒刺机关——小心拆除后，箱子里的宝贝完好无损！",
                    Rewards = new() { ["gold"] = 100, ["elixir"] = 1 },
                    Condition = new Tag.TagConditionParser().Parse("HasTag(谨慎)") },
                new() { Label = "绕开它继续赶路", ResultText = "你不是来寻宝的。木箱静静留在树根下，等待下一个发现它的人。" }
            }
        });

        _pool.Add(new EncounterEvent
        {
            Id = "wandering_priest",
            Description = "一位身披白袍的圣殿祭司迎面走来，他微微欠身：「旅人，你是否愿意接受光明神的祝福？」",
            MinLevel = 1, MaxLevel = 99, Weight = 8, CooldownSteps = 20,
            Options =
            {
                new() { Label = "接受祝福", ResultText = "祭司将手按在你的额上，柔和的金光笼罩了你。你的伤势和疲惫都消退了。",
                    Rewards = new() { ["reputation_holy"] = 5, ["healing_potion"] = 1 } },
                new() { Label = "婉拒（你不信这些）", ResultText = "你摆摆手，祭司也不强求，笑着与你擦肩而过，留下一句「愿你平安」。",
                    Penalties = new() { ["hp"] = -2 } },
            }
        });

        _pool.Add(new EncounterEvent
        {
            Id = "abandoned_camp",
            Description = "你发现了一处被匆忙遗弃的营地——篝火还冒着青烟，地上散落着补给品。",
            MinLevel = 2, MaxLevel = 99, Weight = 8, CooldownSteps = 10,
            Options =
            {
                new() { Label = "拿走补给", ResultText = "你在帐篷里找到了干粮和几枚银币。",
                    Rewards = new() { ["travel_rations"] = 2, ["gold"] = 40 } },
                new() { Label = "等候失主归来", ResultText = "你坐在篝火旁守候了半晌。夕阳西下时，一队猎人归来，感激地分给了你一块烤肉。",
                    Rewards = new() { ["cooked_meat"] = 2, ["reputation_ranger"] = 3 },
                    Penalties = new() { ["hunger"] = -5, ["thirst"] = -3 } },
            }
        });

        _pool.Add(new EncounterEvent
        {
            Id = "miner_escape",
            Description = "矿工打扮的男子跌跌撞撞从山坡小道冲下来，哭着喊道：「塌方了！老三还压在里面——谁来帮帮我！」",
            MinLevel = 3, MaxLevel = 99, Weight = 6, CooldownSteps = 25,
            Options =
            {
                new() { Label = "冲进矿道救人", ResultText = "你在落石中拖出了奄奄一息的老三。矿工们把你当成英雄，塞给你许多报酬。",
                    Rewards = new() { ["gold"] = 200, ["stone"] = 5, ["reputation_citizen"] = 10 },
                    Penalties = new() { ["hp"] = -15 } },
                new() { Label = "问问有没有报酬再帮忙", ResultText = "你冷静地在他面前停下：「多少钱？」矿工咬牙喊道：「两百金币！求你快点！」",
                    Rewards = new() { ["gold"] = 200 }, Penalties = new() { ["hp"] = -8 } },
            }
        });

        _pool.Add(new EncounterEvent
        {
            Id = "thief_ambush",
            Description = "灌木丛中呼地窜出一道黑影——「把值钱的东西交出来！」——是一个持匕首的蒙面人。",
            MinLevel = 2, MaxLevel = 99, Weight = 6, CooldownSteps = 18,
            Options =
            {
                new() { Label = "拔剑迎战", ResultText = "你抽出武器迎了上去……三回合后，蒙面人倒在草丛中，口袋里掉出一袋宝石。",
                    Rewards = new() { ["gold"] = 100, ["magic_crystal"] = 1 }, Penalties = new() { ["hp"] = -12 } },
                new() { Label = "交出钱包保平安", ResultText = "你把钱袋扔过去，蒙面人捡起就跑。虽丢了些金币，但人没事。",
                    Penalties = new() { ["gold"] = -80 } },
                new() { Label = "试图说服他放下武器", ResultText = "「我也是穷苦人家出身，」你沉声道，「我有更好的活计可以介绍你。」蒙面人迟疑了……",
                    Rewards = new() { ["reputation_citizen"] = 3 } },
            }
        });
    }

    /// <summary>根据坐标和等级抽取遭遇事件。返回 null 表示无事发生。</summary>
    public static EncounterEvent Roll(MapPos pos, int playerLevel, Random rng)
    {
        // 基础触发概率 15%，随距离村庄远近递增（荒野更容易遇到事）
        var villageCenter = new MapPos(25, 25);
        var dist = pos.DistanceTo(villageCenter);
        var baseChance = Math.Min(0.35, 0.10 + dist * 0.008);

        if (rng.NextDouble() > baseChance) return null;

        // 从池中捞出满足等级且未冷却的事件
        var valid = _pool
            .Where(e => playerLevel >= e.MinLevel && playerLevel <= e.MaxLevel)
            .Where(e => !_cooldowns.TryGetValue(e.Id, out var cd) || cd <= 0)
            .ToList();
        if (valid.Count == 0) return null;

        // 加权随机
        var totalWeight = valid.Sum(e => e.Weight);
        var roll = rng.NextDouble() * totalWeight;
        var acc = 0.0;
        foreach (var e in valid)
        {
            acc += e.Weight;
            if (roll <= acc) return e;
        }
        return valid[0];
    }

    /// <summary>设置冷却。VM 调用完后调用此方法。</summary>
    public static void SetCooldown(EncounterEvent e)
    {
        if (e.CooldownSteps > 0)
            _cooldowns[e.Id] = e.CooldownSteps;
    }

    /// <summary>每步递减所有冷却（VM 在移动循环中调用）。</summary>
    public static void TickAll()
    {
        foreach (var key in _cooldowns.Keys.ToList())
            if (_cooldowns[key] > 0)
                _cooldowns[key]--;
    }
}

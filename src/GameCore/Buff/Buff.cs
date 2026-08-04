using GameCore.Effect;

namespace GameCore.Buff;

/// <summary>Buff — 运行时Buff实例（由 BuffDef 构造）。</summary>
public sealed class Buff
{
    public string Id { get; }
    public string Name { get; }
    public BuffType Type { get; }
    public int DefaultDuration { get; }
    public int RemainingDuration { get; set; }          // 剩余回合，-1=永久
    public IReadOnlyList<IEffectDef> Effects { get; }
    public bool Stackable { get; }
    public int MaxStacks { get; }
    public int CurrentStacks { get; set; } = 1;

    public Buff(BuffDef def)
    {
        Id = def.Id;
        Name = def.Name;
        Type = def.Type;
        DefaultDuration = def.DefaultDuration;
        RemainingDuration = def.DefaultDuration;
        Effects = def.Effects;
        Stackable = def.Stackable;
        MaxStacks = def.MaxStacks;
    }

    public bool IsExpired() => Type != BuffType.Permanent && RemainingDuration == 0;

    public void TickDown()
    {
        if (Type != BuffType.Permanent && RemainingDuration > 0) RemainingDuration--;
    }

    public void Refresh() => RemainingDuration = DefaultDuration;

    public bool CanStack() => Stackable && CurrentStacks < MaxStacks;

    public override string ToString() => $"{Name}({Id})x{CurrentStacks} 剩余{RemainingDuration}";
}

/// <summary>BuffFactory — 从 BuffDef/装备/触发器 创建 Buff。</summary>
public sealed class BuffFactory
{
    private readonly EffectParser _effectParser;

    public BuffFactory(EffectParser parser)
    {
        _effectParser = parser;
    }

    public Buff Create(string buffId) => new(BuffRegistry.Instance.Get(buffId));

    /// <summary>标签 TRIGGER 效果创建 Buff 的静态入口（EffectEngine.ExecuteTriggerAction 使用）。</summary>
    public static Buff CreateFromRegistry(string buffId, int duration)
    {
        var buff = new Buff(BuffRegistry.Instance.Get(buffId));
        if (duration >= 0) buff.RemainingDuration = duration;
        return buff;
    }

    /// <summary>从装备创建永久Buff（装备不进 TagSet，走 Buff 系统）。</summary>
    public static Buff CreateFromEquip(string equipId, string equipName, IReadOnlyList<IEffectDef> effects)
        => new(new BuffDef(equipId + "_buff", equipName, BuffType.Permanent, -1, effects, false, 1));

    /// <summary>从标签TRIGGER创建触发Buff。</summary>
    public static Buff CreateFromTrigger(TriggerDef trigger)
        => new(new BuffDef(
            trigger.Params.TryGetValue("buffId", out var b) ? b.ToString() : "trigger_buff",
            "trigger_buff",
            BuffType.Triggered,
            trigger.Params.TryGetValue("duration", out var d) ? Convert.ToInt32(d) : 3,
            Array.Empty<IEffectDef>(),   // TRIGGER 的 action 效果在 EffectEngine 中处理
            false, 1));

    /// <summary>套装Buff（N件套效果，永久生效）。</summary>
    public static Buff CreateFromSetBonus(string setId, string bonusName, IReadOnlyList<IEffectDef> effects)
        => new(new BuffDef(setId + "_bonus", bonusName, BuffType.Permanent, -1, effects, false, 1));

    /// <summary>场景Buff（进入场景时附加）。</summary>
    public static Buff CreateScene(string id, string name, IReadOnlyList<IEffectDef> effects)
        => new(new BuffDef(id, name, BuffType.Scene, -1, effects, false, 1));
}

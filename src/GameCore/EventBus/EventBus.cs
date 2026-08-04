using System.Collections.Concurrent;

namespace GameCore.EventBus;

/// <summary>
/// 事件载体。对应设计文档《EventBus设计》中的 Event record。
/// </summary>
public sealed record Event(string Type, object Source, Dictionary<string, object> Payload)
{
    public Unit.Unit Unit => Get<Unit.Unit>("unit");
    public Unit.Unit Target => Get<Unit.Unit>("target");
    public Unit.Unit DeadUnit => Get<Unit.Unit>("deadUnit");
    public Unit.Unit Killer => Get<Unit.Unit>("killer");

    public T Get<T>(string key) where T : class
        => Payload.TryGetValue(key, out var v) ? v as T : null;

    public object GetRaw(string key) => Payload.TryGetValue(key, out var v) ? v : null;
}

/// <summary>预定义的死亡事件构造器。</summary>
public static class DeathEvent
{
    public static Event Of(Unit.Unit dead, Unit.Unit killer) => new(
        EventTypes.UnitDeath, killer,
        new Dictionary<string, object> { ["deadUnit"] = dead, ["killer"] = killer });
}

/// <summary>订阅包装。owner 用于按属主批量清理（recalculateTags 时 unsubscribeAll）。</summary>
public sealed class Subscription
{
    public string Id { get; } = Guid.NewGuid().ToString("N");
    public string EventType { get; }
    public Action<Event> Callback { get; }
    public object Owner { get; }
    public bool Active { get; internal set; } = true;

    public Subscription(string eventType, Action<Event> callback, object owner)
    {
        EventType = eventType;
        Callback = callback;
        Owner = owner;
    }

    public void Invoke(Event e)
    {
        if (Active) Callback(e);
    }
}

/// <summary>EventBus 核心接口：发布/订阅/按属主清理。</summary>
public interface IEventBus
{
    void Emit(string eventType, params object[] payload);
    /// <summary>发射预构造事件（如 DeathEvent.Of，携带 deadUnit/killer 键）。</summary>
    void EmitEvent(Event evt);
    Subscription Subscribe(string eventType, Action<Event> callback);
    Subscription SubscribeWithOwner(string eventType, Action<Event> callback, object owner);
    void UnsubscribeAll(object owner);
    void Unsubscribe(Subscription sub);
}

/// <summary>EventBus 完整实现（同步发射 + 惰性清理）。</summary>
public sealed class EventBusImpl : IEventBus
{
    private readonly ConcurrentDictionary<string, List<Subscription>> _byType = new();
    private readonly ConcurrentDictionary<object, List<Subscription>> _byOwner = new();
    private readonly object _lock = new();

    public void Emit(string eventType, params object[] payload)
        => EmitEvent(BuildEvent(eventType, payload));

    public void EmitEvent(Event evt)
    {
        List<Subscription> subs;
        lock (_lock)
        {
            if (!_byType.TryGetValue(evt.Type, out var stored) || stored.Count == 0) return;
            subs = new List<Subscription>(stored);
        }
        foreach (var sub in subs) sub.Invoke(evt);
    }

    public Subscription Subscribe(string eventType, Action<Event> callback)
        => SubscribeWithOwner(eventType, callback, null);

    public Subscription SubscribeWithOwner(string eventType, Action<Event> callback, object owner)
    {
        var sub = new Subscription(eventType, callback, owner);
        lock (_lock)
        {
            if (!_byType.TryGetValue(eventType, out var list))
            {
                list = new List<Subscription>();
                _byType[eventType] = list;
            }
            list.Add(sub);
            if (owner != null)
            {
                if (!_byOwner.TryGetValue(owner, out var ownerList))
                {
                    ownerList = new List<Subscription>();
                    _byOwner[owner] = ownerList;
                }
                ownerList.Add(sub);
            }
        }
        return sub;
    }

    public void UnsubscribeAll(object owner)
    {
        List<Subscription> subs;
        lock (_lock)
        {
            if (!_byOwner.TryRemove(owner, out subs)) return;
            foreach (var s in subs) s.Active = false;
        }
    }

    public void Unsubscribe(Subscription sub) => sub.Active = false;

    /// <summary>智能 payload 映射：按参数类型自动填入 key（与设计文档一致）。</summary>
    private static Event BuildEvent(string type, object[] payload)
    {
        var map = new Dictionary<string, object>();
        foreach (var obj in payload)
        {
            switch (obj)
            {
                case null: break;
                case Unit.Unit u:
                    if (!map.ContainsKey("target")) map["target"] = u;
                    else if (!map.ContainsKey("source")) map["source"] = u;
                    else map["unit"] = u;
                    break;
                case int or float or double or long: map["amount"] = obj; break;
                case string s: map["text"] = s; break;
                default: map["data"] = obj; break;
            }
        }
        return new Event(type, payload.Length > 0 ? payload[0] : null, map);
    }
}

/// <summary>事件类型全集常量。</summary>
public static class EventTypes
{
    // 战斗
    public const string BattleStart = "BATTLE_START";
    public const string TurnStart = "TURN_START";
    public const string ActionExecuted = "ACTION_EXECUTED";
    public const string DamageDealt = "DAMAGE_DEALT";
    public const string DamageCrit = "DAMAGE_CRIT";
    public const string HpBelow30 = "HP_BELOW_30";
    public const string UnitDeath = "UNIT_DEATH";
    public const string TurnEnd = "TURN_END";
    public const string BattleEnd = "BATTLE_END";
    public const string SkillUsed = "SKILL_USED";
    public const string SkillFailed = "SKILL_FAILED";
    public const string AllyCover = "ALLY_COVER";
    public const string AllyBetray = "ALLY_BETRAY";

    // Buff
    public const string BuffApplied = "BUFF_APPLIED";
    public const string BuffRemoved = "BUFF_REMOVED";
    public const string BuffExpired = "BUFF_EXPIRED";

    // 情感
    public const string AllyDeath = "ALLY_DEATH";
    public const string Surrounded = "SURROUNDED";
    public const string EnemyKilled = "ENEMY_KILLED";

    // 系统
    public const string TagChanged = "TAG_CHANGED";
    public const string RaceChanged = "RACE_CHANGED";
    public const string ClassChanged = "CLASS_CHANGED";
    public const string QuestCompleted = "QUEST_COMPLETED";
    public const string NpcInteraction = "NPC_INTERACTION";
    public const string ItemAcquired = "ITEM_ACQUIRED";
    public const string ItemUsed = "ITEM_USED";
    public const string EquipBroken = "EQUIP_BROKEN";
    public const string GameLoaded = "GAME_LOADED";
    public const string ContractExpired = "CONTRACT_EXPIRED";
    public const string HomeLevelUp = "HOME_LEVEL_UP";
    public const string PlayerMoved = "PLAYER_MOVED";
    public const string AreaEntered = "AREA_ENTERED";
    public const string GameQuit = "GAME_QUIT";

    // 生存
    public const string HungerCritical = "HUNGER_CRITICAL";
    public const string ThirstCritical = "THIRST_CRITICAL";
    public const string TempCritical = "TEMP_CRITICAL";
}

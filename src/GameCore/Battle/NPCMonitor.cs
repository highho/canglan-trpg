using GameCore.EventBus;
using GameCore.Unit;

namespace GameCore.Battle;

/// <summary>对话提供者抽象 — 预留 NPC 对话树注入点（NPC系统模块实现后替换）。</summary>
public interface IDialogueProvider
{
    /// <summary>触发一次对话（返回对话文本；UI/演示层负责展示）。</summary>
    string TriggerDialogue(Unit.Unit speaker, string dialogueKey, object context);
}

/// <summary>缺省对话提供者：直接返回模板文本（演示/测试用）。</summary>
public sealed class DefaultDialogueProvider : IDialogueProvider
{
    public string TriggerDialogue(Unit.Unit speaker, string dialogueKey, object context)
    {
        var text = dialogueKey switch
        {
            "LOW_HP" => $"{speaker.Name}：我快撑不住了……",
            "ALLY_DEATH" => $"{speaker.Name}：不！{(context as Unit.Unit)?.Name}！",
            "BATTLE_WIN" => $"{speaker.Name}：我们赢了！",
            "BATTLE_LOSE" => $"{speaker.Name}：撤！下次再来……",
            "RECRUIT" => $"{speaker.Name}：你救了我，我愿意跟随你。",
            _ => $"{speaker.Name}：……"
        };
        Console.WriteLine($"[对话] {text}");
        return text;
    }
}

/// <summary>
/// NPCMonitor — 战斗中的NPC监听模式（设计文档《战斗系统设计》NPC监听章节）。
/// 以监听者身份订阅战斗事件（owner=this，不随标签重建清理）：
///   HP_BELOW_30 → 低血量对话；UNIT_DEATH → 记录死亡名单 + 队友死亡对话 + 悲伤情感；
///   BATTLE_END  → 战后响应（胜利/失败对话、感情线招募判定）。
/// 对话介入时暂停战斗（battle.Pause），对话结束后恢复（battle.Resume）。
/// FullListener 支持感情招募判定；雇佣兵走精简版（仅死亡/战后通知）。
/// </summary>
public sealed class NPCMonitor
{
    /// <summary>该NPC关注/有感情线的单位集合（感情招募判定对象）。</summary>
    public HashSet<Unit.Unit> WatchedUnits { get; } = new();

    /// <summary>是否完整监听（false = 雇佣兵精简版：仅死亡与战后通知）。</summary>
    public bool FullListener { get; init; } = true;

    private readonly Unit.Unit _npc;
    private readonly IDialogueProvider _dialogue;
    private readonly IEventBus _bus;
    private BattleManager _battle;
    private bool _attached;

    public NPCMonitor(Unit.Unit npc, IEventBus bus, IDialogueProvider dialogue = null)
    {
        _npc = npc;
        _bus = bus;
        _dialogue = dialogue ?? new DefaultDialogueProvider();
    }

    /// <summary>挂载到战斗：注册事件监听并记录战斗引用。</summary>
    public void Attach(BattleManager battle)
    {
        if (_attached) return;
        _attached = true;
        _battle = battle;

        if (FullListener)
        {
            _bus.SubscribeWithOwner(EventTypes.HpBelow30, OnHpBelow30, this);
            _bus.SubscribeWithOwner(EventTypes.AllyDeath, OnAllyDeath, this);
        }
        _bus.SubscribeWithOwner(EventTypes.UnitDeath, OnUnitDeath, this);
        _bus.SubscribeWithOwner(EventTypes.BattleEnd, OnBattleEnd, this);
    }

    public void Detach()
    {
        if (!_attached) return;
        _attached = false;
        _bus.UnsubscribeAll(this);
    }

    // ==================== 事件处理 ====================

    private void OnHpBelow30(Event e)
    {
        var unit = e.Target ?? e.Unit;
        if (unit == null || !IsWatchedOrSelf(unit)) return;
        TriggerDialogue("LOW_HP", unit);
    }

    private void OnUnitDeath(Event e)
    {
        var dead = e.DeadUnit ?? e.Target;
        if (dead == null) return;
        // 雇佣兵精简版：仅记录，不触发感情对话
        if (!FullListener) return;
        if (dead.Role == UnitRole.Ally || WatchedUnits.Contains(dead))
            _bus.Emit(EventTypes.AllyDeath, dead, _npc);
    }

    private void OnAllyDeath(Event e)
    {
        var dead = e.Target ?? e.DeadUnit;
        if (dead == null || !IsWatchedOrSelf(dead)) return;
        // 悲伤情感 80 → 活跃标签变化 → 重建标签
        _npc.Emotion.ApplyEmotion(EmotionSystem.Sorrow, 80);
        _npc.RecalculateTags();
        TriggerDialogue("ALLY_DEATH", dead);
    }

    private void OnBattleEnd(Event e)
    {
        var result = e.Get<BattleResult>("data") ?? _battle?.Result;
        Detach();
        if (result == null) return;

        TriggerDialogue(result.PlayerWin ? "BATTLE_WIN" : "BATTLE_LOSE", null);

        // 感情线招募：被关注的存活单位且好感度达标 → 招募对话
        if (FullListener && result.PlayerWin)
        {
            foreach (var watched in WatchedUnits.ToList())
            {
                if (watched.IsDead || watched.Role == UnitRole.Ally) continue;
                if (_npc.GetAllyAffinity(watched) >= 30)
                {
                    TriggerDialogue("RECRUIT", watched);
                    _bus.Emit(EventTypes.NpcInteraction, _npc, watched);
                }
            }
        }
    }

    // ==================== 对话介入 ====================

    /// <summary>触发对话：暂停战斗 → 对话 → 恢复战斗。</summary>
    private void TriggerDialogue(string key, Unit.Unit context)
    {
        var inBattle = _battle is { Paused: false };
        if (inBattle) _battle.Pause();
        _dialogue.TriggerDialogue(_npc, key, context);
        if (inBattle) _battle.Resume();
    }

    private bool IsWatchedOrSelf(Unit.Unit unit)
        => ReferenceEquals(unit, _npc) || WatchedUnits.Contains(unit);
}

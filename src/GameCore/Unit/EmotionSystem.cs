using GameCore.EventBus;

namespace GameCore.Unit;

/// <summary>情感变更定义（emotionTriggers.json 的条目）。</summary>
public sealed record EmotionChange(string EmotionId, int Intensity);

/// <summary>
/// EmotionSystem — 情感动态管理。
/// 四维数值（恐惧/愤怒/悲伤/喜悦）0-100，> 50 阈值才成为活跃 EMOTION 标签。
/// 跷跷板衰减：提升一种情感会压制对立情感。
/// 订阅属主 = 本实例（不会被 Unit.RecalculateTags 的 unsubscribeAll(this) 清理）。
/// </summary>
public sealed class EmotionSystem
{
    public const string Fear = "恐惧";
    public const string Anger = "愤怒";
    public const string Sorrow = "悲伤";
    public const string Joy = "喜悦";

    private readonly Unit _owner;
    private int _fearLevel;     // 0-100
    private int _angerLevel;    // 0-100
    private int _sorrowLevel;   // 0-100
    private int _joyLevel;      // 0-100
    private readonly HashSet<string> _survivalEmotions = new();   // 生存状态标签（饥饿/干渴/冻伤）

    public int FearLevel => _fearLevel;
    public int AngerLevel => _angerLevel;
    public int SorrowLevel => _sorrowLevel;
    public int JoyLevel => _joyLevel;

    public EmotionSystem(Unit owner, IEventBus bus)
    {
        _owner = owner;
        RegisterListeners(bus);
    }

    private void RegisterListeners(IEventBus bus)
    {
        bus.SubscribeWithOwner(EventTypes.DamageCrit, e =>
        {
            if (e.Target == _owner) ApplyEmotion(Anger, 40);
        }, this);
        bus.SubscribeWithOwner(EventTypes.AllyDeath, _ => ApplyEmotion(Sorrow, 60), this);
        bus.SubscribeWithOwner(EventTypes.Surrounded, e =>
        {
            if (e.Target == _owner) ApplyEmotion(Fear, 30);
        }, this);
        bus.SubscribeWithOwner(EventTypes.EnemyKilled, e =>
        {
            if (ReferenceEquals(e.Source, _owner) || e.Target == _owner) ApplyEmotion(Joy, 20);
        }, this);
        bus.SubscribeWithOwner(EventTypes.HpBelow30, e =>
        {
            if (e.Target == _owner || e.Unit == _owner) ApplyEmotion(Fear, 50);
        }, this);
    }

    /// <summary>提升情感强度并跷跷板衰减对立情感。调用方负责 recalculateTags。</summary>
    public void ApplyEmotion(string emotionId, int intensity)
    {
        switch (emotionId)
        {
            case Fear: _fearLevel = Math.Min(100, _fearLevel + intensity); break;
            case Anger: _angerLevel = Math.Min(100, _angerLevel + intensity); break;
            case Sorrow: _sorrowLevel = Math.Min(100, _sorrowLevel + intensity); break;
            case Joy: _joyLevel = Math.Min(100, _joyLevel + intensity); break;
        }
        DecayOpposing(emotionId, intensity / 2);
    }

    private void DecayOpposing(string emotionId, int amount)
    {
        switch (emotionId)
        {
            case Anger: _joyLevel = Math.Max(0, _joyLevel - amount); break;
            case Joy: _angerLevel = Math.Max(0, _angerLevel - amount); break;
            case Fear: _angerLevel = Math.Max(0, _angerLevel - amount / 2); break;
        }
    }

    /// <summary>每回合自然衰减，返回是否有活跃情感数量变化（true → 需要 RecalculateTags）。</summary>
    public bool TickDecay()
    {
        var before = ActiveEmotionIds().Count;
        _fearLevel = Math.Max(0, _fearLevel - 5);
        _angerLevel = Math.Max(0, _angerLevel - 5);
        _sorrowLevel = Math.Max(0, _sorrowLevel - 8);
        _joyLevel = Math.Max(0, _joyLevel - 5);
        return ActiveEmotionIds().Count != before;
    }

    /// <summary>生存状态标签挂接/移除（饥饿/干渴/冻伤，由生存系统临界值判定驱动）。</summary>
    public void SetSurvivalEmotion(string tagId, bool active)
    {
        if (active) _survivalEmotions.Add(tagId);
        else _survivalEmotions.Remove(tagId);
    }

    /// <summary>> 50 阈值的情感才成为活跃 EMOTION 标签（含生存状态标签）。</summary>
    public HashSet<string> ActiveEmotionIds()
    {
        var ids = new HashSet<string>(_survivalEmotions);
        if (_fearLevel > 50) ids.Add(Fear);
        if (_angerLevel > 50) ids.Add(Anger);
        if (_sorrowLevel > 50) ids.Add(Sorrow);
        if (_joyLevel > 50) ids.Add(Joy);
        return ids;
    }
}

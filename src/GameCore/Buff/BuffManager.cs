using GameCore.Effect;
using GameCore.EventBus;

namespace GameCore.Buff;

/// <summary>
/// BuffManager — Unit 的 Buff 管理器。
/// Buff 不进 TagSet（不污染进化条件），只影响数值叠加层。
/// STAT_MOD 效果通过全量重建 Unit 的 Buff 属性快照来应用/移除（与标签层同样的无状态哲学）。
/// 订阅属主 = 本实例，不会被 RecalculateTags 的 unsubscribeAll(unit) 清理。
/// </summary>
public sealed class BuffManager
{
    private readonly Unit.Unit _owner;
    private readonly IEventBus _eventBus;
    private readonly EffectEngine _effectEngine;
    private readonly List<Buff> _activeBuffs = new();

    public BuffManager(Unit.Unit owner, IEventBus bus, EffectEngine engine)
    {
        _owner = owner;
        _eventBus = bus;
        _effectEngine = engine;
        bus.SubscribeWithOwner(EventTypes.TurnEnd, _ => OnTurnEnd(), this);
    }

    public IReadOnlyList<Buff> GetActiveBuffs() => _activeBuffs.ToList();

    public bool HasBuff(string buffId) => _activeBuffs.Any(b => b.Id == buffId);

    public void AddBuff(Buff buff)
    {
        if (buff == null) return;
        var existing = _activeBuffs.FirstOrDefault(b => b.Id == buff.Id);
        if (existing != null)
        {
            if (existing.CanStack())
            {
                existing.CurrentStacks++;
                existing.Refresh();
            }
            else
            {
                existing.Refresh();   // 不可叠加 → 刷新持续时间
            }
        }
        else
        {
            _activeBuffs.Add(buff);
            // TRIGGER 效果：以 buff 为属主注册监听，移除时统一清理
            _effectEngine.RegisterEffectTriggers(_owner, buff.Effects, _eventBus, buff);
        }
        RebuildStatMods();
        _eventBus.Emit(EventTypes.BuffApplied, _owner, buff);
    }

    public void RemoveBuff(string buffId)
    {
        var buff = _activeBuffs.FirstOrDefault(b => b.Id == buffId);
        if (buff == null) return;
        _activeBuffs.Remove(buff);
        _eventBus.UnsubscribeAll(buff);   // 清理 TRIGGER 监听
        RebuildStatMods();
        _eventBus.Emit(EventTypes.BuffRemoved, _owner, buff);
    }

    /// <summary>回合结束：倒计时 → 清理过期Buff（不触发 recalculateTags）。</summary>
    public void OnTurnEnd()
    {
        foreach (var buff in _activeBuffs) buff.TickDown();
        var expired = _activeBuffs.Where(b => b.IsExpired()).ToList();
        if (expired.Count == 0) return;
        foreach (var buff in expired)
        {
            _activeBuffs.Remove(buff);
            _eventBus.UnsubscribeAll(buff);
            _eventBus.Emit(EventTypes.BuffExpired, _owner, buff);
        }
        RebuildStatMods();
    }

    /// <summary>无状态重建：清空快照后重放所有活跃Buff的 STAT_MOD（含层数倍乘）。</summary>
    private void RebuildStatMods()
    {
        _owner.ResetBuffStats();
        foreach (var buff in _activeBuffs)
        {
            foreach (var effect in buff.Effects)
            {
                if (effect is StatMod s)
                {
                    var value = s.Operator == Operator.Add ? s.Value * buff.CurrentStacks : s.Value;
                    _owner.ApplyBuffStat(s.Target, s.Operator, value);
                }
            }
        }
    }

    /// <summary>伤害加成查询（ATK ADD 效果之和）。</summary>
    public float GetDamageBonus() => SumStatMods("ATK");

    /// <summary>防御加成查询（DEF ADD 效果之和）。</summary>
    public float GetDefenseBonus() => SumStatMods("DEF");

    /// <summary>针对目标的条件增伤倍率（DAMAGE_MOD 效果，伤害计算时查询）。</summary>
    public float GetDamageModMultiplier(Unit.Unit target)
    {
        var multiplier = 1f;
        foreach (var buff in _activeBuffs)
        {
            foreach (var effect in buff.Effects)
            {
                if (effect is DamageMod dm && dm.Matches(target))
                    multiplier = dm.Operator switch
                    {
                        Operator.Add => multiplier + dm.Value,
                        Operator.Multiply => multiplier * dm.Value,
                        Operator.Set => dm.Value,
                        _ => multiplier
                    };
            }
        }
        return multiplier;
    }

    private float SumStatMods(string target)
        => _activeBuffs
            .SelectMany(b => b.Effects)
            .OfType<StatMod>()
            .Where(s => s.Target == target && s.Operator == Operator.Add)
            .Sum(s => s.Value);
}

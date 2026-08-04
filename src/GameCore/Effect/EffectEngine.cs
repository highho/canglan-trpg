using GameCore.EventBus;
using GameCore.Tag;

namespace GameCore.Effect;

/// <summary>
/// EffectEngine — 效果应用到实例。
/// STAT_MOD → 属性快照；DAMAGE_MOD → 条件增伤（伤害计算时查询）；
/// TRIGGER → EventBus 注册监听；FLAG → 纯标记。
/// </summary>
public sealed class EffectEngine
{
    private readonly Random _rng = new();

    /// <summary>属性效果：逐个叠加到 Unit 的标签属性快照。</summary>
    public void ApplyStatMods(Unit.Unit unit, IReadOnlyList<Tag.Tag> tags)
    {
        unit.ResetTagStats();
        foreach (var tag in tags)
        {
            foreach (var effect in tag.Effects)
            {
                if (effect is StatMod s)
                    unit.ApplyTagStat(s.Target, s.Operator, s.Value);
            }
        }
    }

    /// <summary>触发效果：在 EventBus 注册监听（订阅属主 = unit，recalculateTags 时统一清理）。</summary>
    public void RegisterTriggers(Unit.Unit unit, IReadOnlyList<Tag.Tag> tags, IEventBus bus)
    {
        foreach (var tag in tags)
        {
            foreach (var effect in tag.Effects)
            {
                if (effect is TriggerDef t)
                {
                    bus.SubscribeWithOwner(t.On, evt =>
                    {
                        if (!t.ShouldFire(evt, unit, _rng)) return;
                        ExecuteTriggerAction(unit, t, evt, bus);
                    }, unit);
                }
            }
        }
    }

    /// <summary>为任意效果列表（Buff/套装）注册 TRIGGER 监听，属主 = owner（移除时 UnsubscribeAll(owner)）。</summary>
    public void RegisterEffectTriggers(Unit.Unit unit, IReadOnlyList<IEffectDef> effects, IEventBus bus, object owner)
    {
        foreach (var effect in effects)
        {
            if (effect is TriggerDef t)
            {
                bus.SubscribeWithOwner(t.On, evt =>
                {
                    if (!t.ShouldFire(evt, unit, _rng)) return;
                    ExecuteTriggerAction(unit, t, evt, bus);
                }, owner);
            }
        }
    }

    /// <summary>技能/Buff 场景下的通用效果应用：作用在 actor 对 target 上。</summary>
    public void ApplyEffect(Unit.Unit actor, Unit.Unit target, IEffectDef effect, IEventBus bus = null)
    {
        switch (effect)
        {
            case StatMod s:
                // 技能伤害型 STAT_MOD(对目标)：视为属性修正直接作用于目标标签快照
                target.ApplyTagStat(s.Target, s.Operator, s.Value);
                break;
            case DamageMod dm:
                var dmg = Math.Max(1f, dm.Value * 10f); // 基础伤害由调用方主导，这里仅做增伤传递
                if (dm.Matches(target))
                    target.TakeDamage(dmg, actor, bus);
                break;
            case TriggerDef:
                break; // TRIGGER 只在注册路径生效
            case FlagDef:
                break;
        }
    }

    /// <summary>执行 TRIGGER 动作：GAIN_BUFF / HEAL / GAIN_TAG / EMIT_EVENT。</summary>
    public void ExecuteTriggerAction(Unit.Unit owner, TriggerDef trigger, Event evt, IEventBus bus)
    {
        switch (trigger.Action)
        {
            case "GAIN_BUFF":
            case "APPLY_BUFF":
                var buffId = trigger.Params.TryGetValue("buffId", out var b) ? b.ToString() : null;
                var duration = trigger.Params.TryGetValue("duration", out var d) ? Convert.ToInt32(d) : 3;
                if (buffId != null) owner.BuffManager.AddBuff(Buff.BuffFactory.CreateFromRegistry(buffId, duration));
                break;
            case "HEAL":
                var amount = trigger.Params.TryGetValue("amount", out var h) ? Convert.ToInt32(h) : 10;
                owner.Heal(amount);
                break;
            case "GAIN_TAG":
                var tagId = trigger.Params.TryGetValue("tagId", out var g) ? g.ToString() : null;
                if (tagId != null)
                {
                    owner.QuestTagIds.Add(tagId);
                    owner.RecalculateTags();
                }
                break;
            case "EMIT_EVENT":
                var eventType = trigger.Params.TryGetValue("eventType", out var e) ? e.ToString() : null;
                if (eventType != null) bus?.Emit(eventType, owner);
                break;
        }
    }
}

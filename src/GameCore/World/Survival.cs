using GameCore.Effect;
using GameCore.EventBus;

namespace GameCore.World;

/// <summary>地形生态（含基础体温）。</summary>
public enum BiomeType
{
    Plains = 80,
    Forest = 70,
    Desert = 40,
    Tundra = 30,
    Swamp = 60,
    Mountain = 50
}

/// <summary>
/// SurvivalStats — 生存数值管理（饱食度/水分/体温）。
/// 临界值发射 *_CRITICAL 事件 → EmotionSystem 添加 [饥饿]/[干渴]/[冻伤] 标签；
/// 归零惩罚以永久Buff方式施加（无状态可重建，避免重复乘法污染）。
/// </summary>
public sealed class SurvivalStats
{
    private readonly Unit.Unit _owner;
    private readonly IEventBus _eventBus;
    private readonly Random _rng;

    public int Hunger { get; private set; } = 100;        // 饱食度 0-100
    public int Thirst { get; private set; } = 100;        // 水分   0-100
    public int Temperature { get; private set; } = 100;   // 体温   0-100

    public SurvivalStats(Unit.Unit owner, IEventBus bus, Random rng = null)
    {
        _owner = owner;
        _eventBus = bus;
        _rng = rng ?? new Random();
    }

    /// <summary>读档注入存档值（跳过构造函数初始值）。</summary>
    public void Restore(int hunger, int thirst, int temperature)
    {
        Hunger = Math.Clamp(hunger, 0, 100);
        Thirst = Math.Clamp(thirst, 0, 100);
        Temperature = Math.Clamp(temperature, 0, 100);
    }

    /// <summary>每次大地图移动或回合结束时调用。</summary>
    public void Tick(BiomeType biome)
    {
        Hunger = Math.Max(0, Hunger - 1);
        Thirst = Math.Max(0, Thirst - (biome == BiomeType.Desert ? 6 : 2));   // 沙漠×3
        Temperature = (int)biome;

        CheckCritical(EventTypes.HungerCritical, Hunger, 20, "饥饿");
        CheckCritical(EventTypes.ThirstCritical, Thirst, 20, "干渴");
        CheckCritical(EventTypes.TempCritical, Temperature, 20, "冻伤");
    }

    /// <summary>临界值 → 发射事件 + 挂接生存情感标签（≤20 获得，>20 移除）。</summary>
    private void CheckCritical(string eventType, int value, int threshold, string tagId)
    {
        if (value <= threshold)
        {
            _eventBus.Emit(eventType, _owner, value);
            _owner.Emotion.SetSurvivalEmotion(tagId, true);
        }
        else if (value > 20)
        {
            _owner.Emotion.SetSurvivalEmotion(tagId, false);
        }
    }

    /// <summary>消耗食物恢复饱食度（营养值来自 ItemDef.Nutrition）。</summary>
    public void Consume(Item.ItemDef food)
    {
        Hunger = Math.Min(100, Hunger + food.Nutrition);
        if (Hunger > 20) _owner.Emotion.SetSurvivalEmotion("饥饿", false);
        _owner.RecalculateTags();
    }

    /// <summary>饮水恢复水分。</summary>
    public void Drink(int amount)
    {
        Thirst = Math.Min(100, Thirst + amount);
        if (Thirst > 20) _owner.Emotion.SetSurvivalEmotion("干渴", false);
        _owner.RecalculateTags();
    }

    /// <summary>
    /// 归零惩罚（以永久Buff施加，幂等）：
    /// 饱食度0 → HP上限×0.5；水分0 → 移动距离×0.5 + 10%概率眩晕；体温0 → 每回合扣5HP。
    /// </summary>
    public void ApplyPenalties()
    {
        if (Hunger <= 0 && !_owner.BuffManager.HasBuff("starvation"))
        {
            _owner.BuffManager.AddBuff(new Buff.Buff(new Buff.BuffDef("starvation", "饥饿虚弱",
                Buff.BuffType.Permanent, 9999,
                new IEffectDef[] { new StatMod("HP", Operator.Multiply, 0.5f) }, false, 1)));
        }
        if (Thirst <= 0)
        {
            if (!_owner.BuffManager.HasBuff("dehydration"))
            {
                _owner.BuffManager.AddBuff(new Buff.Buff(new Buff.BuffDef("dehydration", "脱水",
                    Buff.BuffType.Permanent, 9999,
                    new IEffectDef[] { new StatMod("MOVE_RANGE", Operator.Multiply, 0.5f) }, false, 1)));
            }
            if (_rng.NextDouble() < 0.1 && !_owner.BuffManager.HasBuff("stun"))
            {
                _owner.BuffManager.AddBuff(new Buff.Buff(new Buff.BuffDef("stun", "眩晕",
                    Buff.BuffType.Temporary, 1, Array.Empty<IEffectDef>(), false, 1)));   // 1回合眩晕
            }
        }
        if (Temperature <= 0) _owner.TakeDamage(5, null, _eventBus);   // 每回合扣5HP
    }
}

/// <summary>
/// SurvivalManager — 生存系统统筹：玩家移动后 tick 生存数值 → 更新迷雾 → 施加惩罚 → 发射 PLAYER_MOVED。
/// </summary>
public sealed class SurvivalManager
{
    private readonly IEventBus _eventBus;

    public SurvivalManager(IEventBus bus)
    {
        _eventBus = bus;
    }

    /// <summary>玩家移动后触发。</summary>
    public void OnPlayerMove(Unit.Unit player, WorldMap map)
    {
        player.Survival.Tick(map.CurrentBiome(player.WorldPos));
        map.CurrentFog().DecayAfterMove(player);
        map.CurrentFog().Update(player);
        player.Survival.ApplyPenalties();
        player.RecalculateTags();   // 生存标签变化 → 重建
        _eventBus.Emit(EventTypes.PlayerMoved, player);
    }
}

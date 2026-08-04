using GameCore.Effect;
using GameCore.EventBus;

namespace GameCore.Equipment;

/// <summary>装备操作结果。</summary>
public sealed record EquipResult(bool Success, Equip Unequipped, string Error, Equip NewEquip = null)
{
    public static EquipResult Ok(Equip old, Equip newEquip = null) => new(true, old, null, newEquip);
    public static EquipResult Fail(string err) => new(false, null, err);
}

/// <summary>修理结果。</summary>
public sealed record RepairResult(bool Success, string Error)
{
    public static RepairResult Ok() => new(true, null);
    public static RepairResult Fail(string err) => new(false, err);
}

/// <summary>
/// EquipmentManager — 装备管理。
/// 铁律：装备永远不进 TagSet。装备效果 = 永久Buff（含 baseStats 转 StatMod），
/// 套装用 N件计数 → 追加套装Buff；穿脱不触发 recalculateTags。
/// </summary>
public sealed class EquipmentManager
{
    private readonly Unit.Unit _owner;
    private readonly Buff.BuffManager _buffManager;
    private readonly IEventBus _eventBus;
    private readonly SetBonusRegistry _setRegistry;
    private readonly Dictionary<EquipSlot, Equip> _equipped = new();
    private readonly Dictionary<string, int> _activeSetBonuses = new();   // setId → 件数

    public EquipmentManager(Unit.Unit owner, IEventBus bus, SetBonusRegistry setRegistry = null)
    {
        _owner = owner;
        _buffManager = owner.BuffManager;
        _eventBus = bus;
        _setRegistry = setRegistry ?? SetBonusRegistry.Instance;
        bus.SubscribeWithOwner(EventTypes.BattleEnd, _ => OnBattleEnd(), this);
    }

    /// <summary>当前已装备映射（slotName → equipId，存档用）。</summary>
    public Dictionary<string, string> GetEquippedMap()
        => _equipped.ToDictionary(kv => kv.Key.ToString(), kv => kv.Value.Id);

    /// <summary>读档恢复：按装备ID重新穿戴（走正常 Equip 流程重建Buff）。</summary>
    public void RestoreEquipped(IEnumerable<string> equipIds)
    {
        foreach (var id in equipIds)
        {
            if (EquipRegistry.Instance != null && EquipRegistry.Instance.TryGet(id, out var def))
                Equip(new Equip(def));
        }
    }

    // ==================== 装备 / 卸下 ====================

    public EquipResult Equip(Equip equip)
    {
        // 1. 耐久检查
        if (equip.IsBroken()) return EquipResult.Fail("装备已损坏，需修理");

        // 2. 标签条件检查（条件评估接口）
        if (equip.EquipCondition != null && !equip.EquipCondition.Evaluate(_owner.ActiveTagIds))
            return EquipResult.Fail("不满足装备条件");

        // 3. 卸下同槽位旧装备
        _equipped.TryGetValue(equip.Slot, out var old);
        if (old != null) Unequip(old);

        // 4. 装备 → 以永久Buff形式添加效果（baseStats 转为 StatMod 一并入Buff，保证无状态重建一致）
        _equipped[equip.Slot] = equip;
        var effects = new List<IEffectDef>(equip.Effects);
        foreach (var kv in equip.BaseStats)
            effects.Add(new StatMod(kv.Key, Operator.Add, kv.Value));
        _buffManager.AddBuff(Buff.BuffFactory.CreateFromEquip(equip.Id, equip.Name, effects));

        // 5. 更新套装计数
        if (equip.SetId != null)
        {
            _activeSetBonuses.TryGetValue(equip.SetId, out var count);
            _activeSetBonuses[equip.SetId] = count + 1;
            CheckSetBonuses();
        }

        // 不触发 recalculateTags ✓ — 装备效果不进TagSet
        return EquipResult.Ok(old);
    }

    public EquipResult Unequip(Equip equip)
    {
        if (!_equipped.ContainsKey(equip.Slot) || !ReferenceEquals(_equipped[equip.Slot], equip))
            return EquipResult.Fail("未装备");

        _equipped.Remove(equip.Slot);
        _buffManager.RemoveBuff(equip.Id + "_buff");

        if (equip.SetId != null)
        {
            _activeSetBonuses.TryGetValue(equip.SetId, out var count);
            count--;
            if (count <= 0)
            {
                _activeSetBonuses.Remove(equip.SetId);
                RemoveSetBonus(equip.SetId);
            }
            else
            {
                _activeSetBonuses[equip.SetId] = count;
            }
            CheckSetBonuses();
        }
        return EquipResult.Ok(null);
    }

    // ==================== 套装检测 ====================

    /// <summary>套装效果：N件同套装 → 追加Buff（先清旧档再上新档）。</summary>
    private void CheckSetBonuses()
    {
        foreach (var kv in _activeSetBonuses)
        {
            var bonus = _setRegistry?.Get(kv.Key, kv.Value);
            if (bonus != null)
            {
                RemoveSetBonus(kv.Key);
                _buffManager.AddBuff(Buff.BuffFactory.CreateFromSetBonus(bonus.SetId, bonus.BonusName, bonus.BonusEffects));
            }
        }
    }

    private void RemoveSetBonus(string setId) => _buffManager.RemoveBuff(setId + "_bonus");

    // ==================== 耐久管理 ====================

    /// <summary>每场战斗后每件装备 -1 耐久；损坏自动卸下并发出事件。</summary>
    public void OnBattleEnd()
    {
        foreach (var equip in _equipped.Values.ToList())
        {
            equip.ConsumeDurability(1);
            if (equip.IsBroken())
            {
                Unequip(equip);
                _eventBus.Emit(EventTypes.EquipBroken, _owner, equip);
            }
        }
    }

    public RepairResult Repair(Equip equip, int amount, int goldCost)
    {
        if (_owner.Gold < goldCost) return RepairResult.Fail("金币不足");
        if (equip.CurrentDurability >= equip.MaxDurability) return RepairResult.Fail("不需要修理");

        _owner.Gold -= goldCost;
        var wasBroken = equip.IsBroken();
        equip.Repair(amount);
        // 修好后自动重新装备（若未在装备状态）
        if (wasBroken && !_equipped.ContainsKey(equip.Slot))
            Equip(equip);
        return RepairResult.Ok();
    }

    // ==================== 查询 ====================

    public Equip Get(EquipSlot slot) => _equipped.TryGetValue(slot, out var e) ? e : null;

    public IReadOnlyDictionary<EquipSlot, Equip> GetAllEquipped() => _equipped;

    public bool HasItem(string equipId) => _equipped.Values.Any(e => e.Id == equipId);
}

/// <summary>
/// EquipmentUpgrade — 升级系统：消耗材料 → 替换为上级装备实例。
/// materialQuery 由制造系统注入（装备ID → 升级材料表），null 表示无需材料。
/// </summary>
public static class EquipmentUpgrade
{
    public static EquipResult Upgrade(Equip equip, Item.Inventory inventory,
        Func<string, IReadOnlyDictionary<string, int>> materialQuery = null)
    {
        if (!equip.CanUpgrade()) return EquipResult.Fail("不可升级");
        if (equip.UpgradePath == null || !EquipRegistry.Instance.TryGet(equip.UpgradePath, out var nextDef))
            return EquipResult.Fail("升级路径不存在");

        var materials = materialQuery?.Invoke(equip.Id);
        if (materials != null)
        {
            if (!inventory.HasItems(materials)) return EquipResult.Fail("材料不足");
            inventory.RemoveAll(materials);
        }
        return EquipResult.Ok(null, new Equip(nextDef));   // 调用方用 Equip(newEquip) 穿上
    }
}

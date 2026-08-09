package com.canglan.world.equipment;

/**
 * 装备操作结果。对应 C# EquipResult。
 * unequipped = 被卸下的旧装备；newEquip = 升级产出的新装备（预留）。
 */
public record EquipResult(boolean success, Equip unequipped, String error, Equip newEquip) {

    public static EquipResult ok(Equip old) { return new EquipResult(true, old, null, null); }
    public static EquipResult ok(Equip old, Equip newEquip) { return new EquipResult(true, old, null, newEquip); }
    public static EquipResult fail(String err) { return new EquipResult(false, null, err, null); }
}

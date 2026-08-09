package com.canglan.data.equipment;

/** 装备槽位。对应 C# EquipSlot。 */
public enum EquipSlot {
    WEAPON, ARMOR, ACCESSORY, RING1, RING2;

    public static EquipSlot parse(String raw) {
        if (raw == null) return WEAPON;
        return switch (raw.toUpperCase()) {
            case "WEAPON" -> WEAPON;
            case "ARMOR" -> ARMOR;
            case "ACCESSORY" -> ACCESSORY;
            case "RING1" -> RING1;
            case "RING2" -> RING2;
            default -> throw new IllegalArgumentException("未知槽位: " + raw);
        };
    }
}

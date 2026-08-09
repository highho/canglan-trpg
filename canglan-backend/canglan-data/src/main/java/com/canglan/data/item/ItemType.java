package com.canglan.data.item;

/** 物品分类。对应 C# ItemType。 */
public enum ItemType {
    MATERIAL, CONSUMABLE, EQUIPMENT, QUEST, MISC;

    /** 大小写不敏感解析，未知归类按 MISC 容错（掉落物/任务物品）。 */
    public static ItemType parse(String raw) {
        if (raw == null) return MISC;
        return switch (raw.toUpperCase()) {
            case "MATERIAL" -> MATERIAL;
            case "CONSUMABLE" -> CONSUMABLE;
            case "EQUIPMENT" -> EQUIPMENT;
            case "QUEST" -> QUEST;
            default -> MISC;
        };
    }
}

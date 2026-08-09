package com.canglan.core.tag;

/** 标签来源白名单：回答「谁能携带这个标签」。对应 C# TagSource。 */
public enum TagSource {
    RACE, CLASS, QUEST, TRAIT, EQUIP, BUFF;

    public static TagSource parse(String raw) {
        return switch (raw.toUpperCase()) {
            case "RACE" -> RACE;
            case "CLASS" -> CLASS;
            case "QUEST" -> QUEST;
            case "TRAIT" -> TRAIT;
            case "EQUIP" -> EQUIP;
            case "BUFF" -> BUFF;
            default -> throw new IllegalArgumentException("未知标签来源: " + raw);
        };
    }
}

package com.canglan.core.tag;

/** 标签六大分类。对应 C# TagCategory。 */
public enum TagCategory {
    /** 元素：属性加成、克制关系。 */
    ELEMENT,
    /** 身份：可用行为集合、社会角色。 */
    IDENTITY,
    /** 人格：行为偏好权重。 */
    PERSONALITY,
    /** 情感：临时行为修正（高频变化）。 */
    EMOTION,
    /** 任务标记：任务链分支条件（不可逆）。 */
    QUEST_MARK,
    /** 技能：战斗行为。 */
    SKILL;

    public static TagCategory parse(String raw) {
        return switch (raw.toUpperCase()) {
            case "ELEMENT" -> ELEMENT;
            case "IDENTITY" -> IDENTITY;
            case "PERSONALITY" -> PERSONALITY;
            case "EMOTION" -> EMOTION;
            case "QUEST_MARK" -> QUEST_MARK;
            case "SKILL" -> SKILL;
            default -> throw new IllegalArgumentException("未知标签分类: " + raw);
        };
    }
}

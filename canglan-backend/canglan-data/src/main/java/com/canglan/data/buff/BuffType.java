package com.canglan.data.buff;

/** Buff 分类。对应 C# BuffType。 */
public enum BuffType {
    /** 永久Buff：装备穿戴期间生效，卸下时移除。 */
    PERMANENT,
    /** 临时Buff：N回合后自然过期。 */
    TEMPORARY,
    /** 触发Buff：由标签TRIGGER效果创建，条件解除时自动移除。 */
    TRIGGERED,
    /** 场景Buff：进入场景时附加，离开时移除。 */
    SCENE;

    public static BuffType parse(String raw) {
        if (raw == null) return TEMPORARY;
        return switch (raw.toUpperCase()) {
            case "PERMANENT" -> PERMANENT;
            case "TEMPORARY" -> TEMPORARY;
            case "TRIGGERED" -> TRIGGERED;
            case "SCENE" -> SCENE;
            default -> throw new IllegalArgumentException("未知Buff类型: " + raw);
        };
    }
}

package com.canglan.world.social;

/** 阵营枚举：游骑兵/冒险公会/商盟/暗影/圣殿/平民。对应 C# FactionId。 */
public enum FactionId {
    RANGER,       // 游骑兵：狩猎/森林/驯兽任务
    ADVENTURER,   // 冒险者公会：讨伐/护送/探索
    MERCHANT,     // 商盟：运输/矿石/制造
    SHADOW,       // 暗影：潜行/暗杀/盗窃
    HOLY,         // 圣殿：治疗/驱魔/誓约
    CITIZEN;      // 平民声望：救助/说服/日常

    /** 首字母大写显示名（拼 [阵营声望xx] 标签用，对应 C# 枚举 ToString）。 */
    public String displayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}

package com.canglan.world.ally;

import com.canglan.core.tag.TagCondition;

/**
 * 招募定义（recruitment.json / NPC元数据）。对应 C# RecruitmentDef。
 * 感情招募 = 标签条件 + 最低好感度；雇佣 = 金币 + 合约期。
 */
public record RecruitmentDef(
        TagCondition bondCondition,
        int minAffinity,
        boolean allowHire,
        int hireCost,
        int contractDuration) {
}

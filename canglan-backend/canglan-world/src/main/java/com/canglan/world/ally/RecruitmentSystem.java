package com.canglan.world.ally;

import com.canglan.world.unit.Unit;
import com.canglan.world.unit.UnitRole;

/**
 * RecruitmentSystem — 招募系统。感情招募 = 标签条件 + 好感度；雇佣 = 金币 + 合约。
 * 招募定义存于 target.metadata()["recruitmentDef"]。对应 C# RecruitmentSystem。
 */
public final class RecruitmentSystem {

    /** 招募类型：感情招募 / 雇佣。对应 C# RecruitmentType。 */
    public enum RecruitmentType { BOND, MERCENARY }

    private RecruitmentSystem() {}

    public static RecruitmentDef getRecruitmentDef(Unit target) {
        Object raw = target.metadata().get("recruitmentDef");
        return raw instanceof RecruitmentDef def ? def : null;
    }

    /** 感情招募：标签条件 + 好感度检查 → 成功转为队友。 */
    public static RecruitmentResult recruitByBond(Unit player, Unit target) {
        RecruitmentDef def = getRecruitmentDef(target);
        if (def == null) return RecruitmentResult.fail("该角色无法招募");

        if (!def.bondCondition().evaluate(player.activeTagIds()))
            return RecruitmentResult.fail("不满足标签条件");

        int affinity = target.affinity();
        if (affinity < def.minAffinity())
            return RecruitmentResult.fail("好感度不足: " + affinity + "/" + def.minAffinity());

        target.setRole(UnitRole.ALLY);
        target.setMercenary(false);
        target.metadata().put("recruitmentType", RecruitmentType.BOND);
        return RecruitmentResult.ok("成功招募 " + target.name());
    }

    /** 雇佣：金币 + 合约条件 → 限时队友。 */
    public static RecruitmentResult hire(Unit player, Unit target) {
        RecruitmentDef def = getRecruitmentDef(target);
        if (def == null) return RecruitmentResult.fail("该角色无法雇佣");
        if (!def.allowHire()) return RecruitmentResult.fail(target.name() + "不接受雇佣");
        if (player.gold() < def.hireCost())
            return RecruitmentResult.fail("金币不足: 需要 " + def.hireCost() + "（你有 " + player.gold() + "）");

        player.setGold(player.gold() - def.hireCost());
        target.setRole(UnitRole.ALLY);
        target.setMercenary(true);
        target.setHireCost(def.hireCost());
        target.setContractDuration(def.contractDuration());
        target.metadata().put("recruitmentType", RecruitmentType.MERCENARY);
        return RecruitmentResult.ok("成功雇佣 " + target.name() + "（合约 " + def.contractDuration() + " 回合）");
    }
}

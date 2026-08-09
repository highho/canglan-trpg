package com.canglan.data.skill;

import com.canglan.core.graph.GraphNode;

/** 技能节点数据（技能也用图结构）。对应 C# SkillData / SkillNode。 */
public record SkillData(Skill skill) {

    public static final class SkillNode extends GraphNode<SkillData> {
        public SkillNode(String id, SkillData data) {
            super(id, data);
        }
    }
}

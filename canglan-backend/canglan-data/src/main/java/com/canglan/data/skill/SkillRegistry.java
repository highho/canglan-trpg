package com.canglan.data.skill;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.canglan.core.effect.EffectParser;
import com.canglan.core.json.JsonReader;
import com.canglan.core.json.JsonValue;
import com.canglan.core.tag.TagCondition;
import com.canglan.core.tag.TagConditionParser;

/** 技能注册表（skills.json）。对应 C# SkillRegistry。 */
public final class SkillRegistry {

    private final Map<String, Skill> skillsById = new LinkedHashMap<>();

    public void loadFromText(String json, EffectParser effectParser, TagConditionParser conditionParser) {
        JsonValue root = JsonReader.parse(json);
        for (Map.Entry<String, JsonValue> entry : root.asObject().entrySet()) {
            String id = entry.getKey();
            JsonValue node = entry.getValue();
            JsonValue uc = node.get("unlockCondition");
            TagCondition unlock = (uc != null && uc.isString()) ? conditionParser.parse(uc.asString()) : null;
            Skill skill = new Skill(
                    id,
                    node.getString("name", id),
                    SkillType.parse(node.getString("type", "ACTIVE")),
                    node.getInt("cooldown", 0),
                    TargetPattern.parse(node.getString("targetPattern", "SINGLE")),
                    effectParser.parseEffects(node),
                    unlock,
                    node.getInt("range", 1),
                    node.getInt("baseDamage", 0),
                    DamageType.parse(node.getString("damageType", "PHYSICAL")));
            register(skill);
        }
    }

    public void register(Skill skill) { skillsById.put(skill.id(), skill); }

    public Skill get(String id) {
        Skill s = skillsById.get(id);
        if (s == null) throw new IllegalArgumentException("未知技能: " + id);
        return s;
    }

    public Skill tryGet(String id) { return skillsById.get(id); }
    public Collection<Skill> getAll() { return skillsById.values(); }
    public int size() { return skillsById.size(); }
}

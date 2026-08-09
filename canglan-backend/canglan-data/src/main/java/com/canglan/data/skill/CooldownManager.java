package com.canglan.data.skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.canglan.core.eventbus.EventBus;
import com.canglan.core.eventbus.EventTypes;

/**
 * CooldownManager — 冷却集中管理。回合结束时所有技能冷却 -1。
 * 对应 C# CooldownManager（属主订阅 TURN_END）。
 */
public final class CooldownManager {

    private final Map<String, Skill> skills = new HashMap<>();

    public CooldownManager(EventBus bus) {
        bus.subscribeWithOwner(EventTypes.TURN_END, e -> {
            for (Skill s : skills.values()) s.tickCooldown();
        }, this);
    }

    public void addSkill(Skill s) { skills.put(s.id(), s); }

    public void removeSkill(String skillId) { skills.remove(skillId); }

    public Skill getSkill(String skillId) { return skills.get(skillId); }

    public boolean canUse(String skillId) {
        Skill s = getSkill(skillId);
        return s != null && s.isReady();
    }

    public List<Skill> getUsableSkills() {
        List<Skill> usable = new ArrayList<>();
        for (Skill s : skills.values()) if (s.isReady()) usable.add(s);
        return usable;
    }

    public List<Skill> getAll() { return new ArrayList<>(skills.values()); }

    /** 存档用：skillId → 剩余冷却。 */
    public Map<String, Integer> toSaveMap() {
        Map<String, Integer> map = new HashMap<>();
        for (Map.Entry<String, Skill> kv : skills.entrySet())
            map.put(kv.getKey(), kv.getValue().currentCooldown());
        return map;
    }
}

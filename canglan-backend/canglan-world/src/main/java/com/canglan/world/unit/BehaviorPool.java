package com.canglan.world.unit;

import java.util.ArrayList;
import java.util.List;

/**
 * 行为池：一组行为选项。socialPool（社交）/ combatPool（战斗）按角色状态切换激活。
 * 对应 C# BehaviorPool。
 */
public final class BehaviorPool {

    private final String id;
    private final String name;
    private final List<BehaviorOption> options = new ArrayList<>();

    public BehaviorPool(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String id() { return id; }
    public String name() { return name; }
    public List<BehaviorOption> options() { return options; }

    public BehaviorPool add(BehaviorOption option) {
        options.add(option);
        return this;
    }

    /** 不存在返回 null。 */
    public BehaviorOption find(String optionId) {
        for (BehaviorOption o : options) {
            if (o.id().equals(optionId)) return o;
        }
        return null;
    }
}

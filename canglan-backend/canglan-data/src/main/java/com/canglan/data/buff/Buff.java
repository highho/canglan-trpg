package com.canglan.data.buff;

import java.util.List;

import com.canglan.core.effect.EffectDef;

/** Buff — 运行时Buff实例（由 BuffDef 构造）。对应 C# Buff。 */
public final class Buff {

    private final String id;
    private final String name;
    private final BuffType type;
    private final int defaultDuration;
    private int remainingDuration;          // 剩余回合，-1=永久
    private final List<EffectDef> effects;
    private final boolean stackable;
    private final int maxStacks;
    private int currentStacks = 1;

    public Buff(BuffDef def) {
        this.id = def.id();
        this.name = def.name();
        this.type = def.type();
        this.defaultDuration = def.defaultDuration();
        this.remainingDuration = def.defaultDuration();
        this.effects = def.effects();
        this.stackable = def.stackable();
        this.maxStacks = def.maxStacks();
    }

    public String id() { return id; }
    public String name() { return name; }
    public BuffType type() { return type; }
    public int defaultDuration() { return defaultDuration; }
    public int remainingDuration() { return remainingDuration; }
    public void setRemainingDuration(int v) { this.remainingDuration = v; }
    public List<EffectDef> effects() { return effects; }
    public boolean stackable() { return stackable; }
    public int maxStacks() { return maxStacks; }
    public int currentStacks() { return currentStacks; }
    public void setCurrentStacks(int v) { this.currentStacks = v; }
    public void incrementStacks() { this.currentStacks++; }

    public boolean isExpired() { return type != BuffType.PERMANENT && remainingDuration == 0; }

    public void tickDown() {
        if (type != BuffType.PERMANENT && remainingDuration > 0) remainingDuration--;
    }

    public void refresh() { remainingDuration = defaultDuration; }

    public boolean canStack() { return stackable && currentStacks < maxStacks; }

    @Override
    public String toString() { return name + "(" + id + ")x" + currentStacks + " 剩余" + remainingDuration; }
}

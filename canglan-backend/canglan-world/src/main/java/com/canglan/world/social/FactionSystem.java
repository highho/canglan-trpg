package com.canglan.world.social;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * FactionSystem — 阵营声望管理。对应 C# FactionSystem
 * （静态 Instance 改为实例持有；ReputationChanged 事件改为监听器回调）。
 */
public final class FactionSystem {

    /** 声望变化监听：(阵营, 本次变化量, 变化后声望等级)。 */
    @FunctionalInterface
    public interface ReputationListener {
        void onChanged(FactionId faction, int delta, RepLevel newLevel);
    }

    private final Map<FactionId, Integer> rep = new EnumMap<>(FactionId.class);
    private ReputationListener listener;

    public FactionSystem() {
        for (FactionId f : FactionId.values()) rep.put(f, 0);   // 初始中立
    }

    public void setListener(ReputationListener listener) { this.listener = listener; }

    /** 获取声望值。 */
    public int get(FactionId fid) { return rep.getOrDefault(fid, 0); }

    /** 从 reward 键 "reputation_xxx" 中加声望（xxx=ranger/adventurer/merchant/shadow/holy/citizen）。 */
    public void add(String rewardKey, int amount) {
        FactionId fid = parseFaction(rewardKey);
        if (fid == null) return;
        add(fid, amount);
    }

    public void add(FactionId fid, int amount) {
        rep.put(fid, Math.max(-999, Math.min(999, get(fid) + amount)));
        if (listener != null) listener.onChanged(fid, amount, getLevel(fid));
    }

    /** 获取声望等级。 */
    public RepLevel getLevel(FactionId fid) {
        int v = get(fid);
        if (v < -50) return RepLevel.HOSTILE;
        if (v < 100) return RepLevel.NEUTRAL;
        if (v < 300) return RepLevel.FRIENDLY;
        if (v < 600) return RepLevel.HONORED;
        return RepLevel.REVERED;
    }

    /** 获取对应[声望Lv]标签ID（用于 VM/Condition 检查）。 */
    public String getTagId(FactionId fid) {
        return switch (getLevel(fid)) {
            case HOSTILE -> fid.displayName() + "声望敌视";
            case FRIENDLY -> fid.displayName() + "声望友善";
            case HONORED -> fid.displayName() + "声望尊敬";
            case REVERED -> fid.displayName() + "声望崇敬";
            default -> fid.displayName() + "声望中立";
        };
    }

    /** 初始化/读档恢复。 */
    public void restore(Map<FactionId, Integer> saved) {
        if (saved == null) return;
        for (Map.Entry<FactionId, Integer> kv : saved.entrySet()) rep.put(kv.getKey(), kv.getValue());
    }

    public Map<FactionId, Integer> snapshot() { return new EnumMap<>(rep); }

    private static FactionId parseFaction(String key) {
        if (key == null || key.isEmpty()) return null;
        if (key.startsWith("reputation_")) key = key.substring("reputation_".length());
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "ranger" -> FactionId.RANGER;
            case "adventurer" -> FactionId.ADVENTURER;
            case "merchant" -> FactionId.MERCHANT;
            case "shadow" -> FactionId.SHADOW;
            case "holy" -> FactionId.HOLY;
            case "citizen" -> FactionId.CITIZEN;
            default -> null;
        };
    }
}

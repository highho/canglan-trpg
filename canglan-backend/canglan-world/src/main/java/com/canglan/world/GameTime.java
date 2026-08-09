package com.canglan.world;

/**
 * GameTime — 游戏内时间系统（开拓者式「第X天 · 春季 · 夜晚」时间栏）。对应 C# GameTime。
 * 每次移动/行动推进一小时；季节按天数轮换；昼夜影响遭遇率、视野与理智消耗。
 */
public final class GameTime {

    public static final int HOURS_PER_DAY = 24;
    public static final int DAYS_PER_SEASON = 7;   // 7天=一季，28天=一年（春→夏→秋→冬）

    private int day = 1;     // 第几天（从1开始）
    private int hour = 6;    // 当前小时 0-23（开局清晨 6 点）

    public int day() { return day; }
    public int hour() { return hour; }

    public Season season() {
        return switch (((day - 1) / DAYS_PER_SEASON) % 4) {
            case 0 -> Season.SPRING;
            case 1 -> Season.SUMMER;
            case 2 -> Season.AUTUMN;
            default -> Season.WINTER;
        };
    }

    public DayPhase phase() {
        if (hour >= 5 && hour <= 7) return DayPhase.DAWN;
        if (hour >= 8 && hour <= 17) return DayPhase.DAY;
        if (hour >= 18 && hour <= 20) return DayPhase.DUSK;
        return DayPhase.NIGHT;
    }

    public boolean isNight() { return phase() == DayPhase.NIGHT || phase() == DayPhase.DAWN; }

    public String display() {
        return "第" + day + "天 · " + seasonName() + " · " + phaseName()
                + "（" + String.format("%02d", hour) + ":00）";
    }

    public String seasonName() {
        return switch (season()) {
            case SPRING -> "春";
            case SUMMER -> "夏";
            case AUTUMN -> "秋";
            case WINTER -> "冬";
        };
    }

    public String phaseName() {
        return switch (phase()) {
            case DAWN -> "黎明";
            case DAY -> "白天";
            case DUSK -> "黄昏";
            case NIGHT -> "夜晚";
        };
    }

    /** 推进若干小时（移动/行动/探索都会消耗时间）。 */
    public void advance(int hours) {
        for (int i = 0; i < hours; i++) {
            hour++;
            if (hour >= HOURS_PER_DAY) {
                hour = 0;
                day++;
            }
        }
    }

    public void advance() { advance(1); }

    /** 时间推进到下一个时段（如「等到天亮」）。 */
    public void advanceTo(DayPhase phase) {
        while (phase() != phase) advance(1);
    }

    /** 读档恢复。 */
    public void restore(int day, int hour) {
        this.day = Math.max(1, day);
        this.hour = Math.max(0, Math.min(23, hour));
    }
}

package com.canglan.ai;

/**
 * AiCircuitBreaker — 熔断器（MIGRATION_PLAN §4.3）。
 * 连续 {@code failureThreshold} 次失败 → 熔断 {@code openMillis} 毫秒 → 期间放行探测（半开）。
 * 线程安全。
 */
public final class AiCircuitBreaker {

    private final int failureThreshold;
    private final long openMillis;
    private int consecutiveFailures;
    private long openUntil;   // 熔断截止时间戳（0 = 闭合）

    public AiCircuitBreaker(int failureThreshold, long openMillis) {
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
    }

    /** 当前是否允许真实调用（闭合/半开放行，熔断中拒绝）。 */
    public synchronized boolean allowRequest() {
        if (openUntil == 0) return true;
        if (System.currentTimeMillis() >= openUntil) {
            openUntil = 0;          // 半开：放行一次探测
            consecutiveFailures = 0;
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntil = 0;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openUntil = System.currentTimeMillis() + openMillis;
        }
    }

    /** 是否处于熔断期（不含半开探测窗口）。 */
    public synchronized boolean isOpen() {
        return openUntil != 0 && System.currentTimeMillis() < openUntil;
    }
}

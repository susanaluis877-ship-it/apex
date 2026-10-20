package com.github.susanaluis877.apex.flow;

import java.util.Objects;

/**
 * 流程级默认值，状态和拦截器没有单独设置时使用。
 */
public final class FlowDefaults {

    public static final long DEFAULT_STATE_TIMEOUT_MS = 10_000;
    public static final long DEFAULT_RETRY_INTERVAL_MS = 3_000;
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final long DEFAULT_ACTION_GAP_MS = 300;
    public static final int DEFAULT_INTERCEPTOR_MAX_TIMES = 5;
    public static final long DEFAULT_INTERCEPTOR_COOLDOWN_MS = 1_500;

    private final long timeoutMs;
    private final long retryIntervalMs;
    private final int maxRetries;
    private final long actionGapMs;
    private final int interceptorMaxTimes;
    private final long interceptorCooldownMs;

    private FlowDefaults(Builder b) {
        this.timeoutMs = b.timeoutMs;
        this.retryIntervalMs = b.retryIntervalMs;
        this.maxRetries = b.maxRetries;
        this.actionGapMs = b.actionGapMs;
        this.interceptorMaxTimes = b.interceptorMaxTimes;
        this.interceptorCooldownMs = b.interceptorCooldownMs;
    }

    /** 状态超时：进入状态后等待下一状态出现的最长时间。 */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /** 页面没有变化时，距上次执行动作多久后重试。 */
    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /** 同一个状态内相邻两个动作之间的间隔。 */
    public long getActionGapMs() {
        return actionGapMs;
    }

    public int getInterceptorMaxTimes() {
        return interceptorMaxTimes;
    }

    /** 同一个拦截器两次触发之间的最短间隔，防止弹窗关闭动画期间重复点击。 */
    public long getInterceptorCooldownMs() {
        return interceptorCooldownMs;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FlowDefaults)) {
            return false;
        }
        FlowDefaults d = (FlowDefaults) o;
        return timeoutMs == d.timeoutMs && retryIntervalMs == d.retryIntervalMs && maxRetries == d.maxRetries
                && actionGapMs == d.actionGapMs && interceptorMaxTimes == d.interceptorMaxTimes
                && interceptorCooldownMs == d.interceptorCooldownMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeoutMs, retryIntervalMs, maxRetries, actionGapMs, interceptorMaxTimes,
                interceptorCooldownMs);
    }

    public static final class Builder {

        private long timeoutMs = DEFAULT_STATE_TIMEOUT_MS;
        private long retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private long actionGapMs = DEFAULT_ACTION_GAP_MS;
        private int interceptorMaxTimes = DEFAULT_INTERCEPTOR_MAX_TIMES;
        private long interceptorCooldownMs = DEFAULT_INTERCEPTOR_COOLDOWN_MS;

        public Builder timeoutMs(long value) {
            timeoutMs = value;
            return this;
        }

        public Builder retryIntervalMs(long value) {
            retryIntervalMs = value;
            return this;
        }

        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        public Builder actionGapMs(long value) {
            actionGapMs = value;
            return this;
        }

        public Builder interceptorMaxTimes(int value) {
            interceptorMaxTimes = value;
            return this;
        }

        public Builder interceptorCooldownMs(long value) {
            interceptorCooldownMs = value;
            return this;
        }

        public FlowDefaults build() {
            return new FlowDefaults(this);
        }
    }
}

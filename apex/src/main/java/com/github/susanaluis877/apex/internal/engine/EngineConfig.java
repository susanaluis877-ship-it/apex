package com.github.susanaluis877.apex.internal.engine;

/**
 * 引擎节奏参数，和具体流程无关。
 */
public final class EngineConfig {

    public static final EngineConfig DEFAULT = new EngineConfig(500, 200, 1_500, 3_000);

    /** 没有界面事件时，两轮之间最长等待时间。 */
    final long pollIntervalMs;
    /** 收到事件后，等界面安静这么久再抓快照。 */
    final long quietMs;
    /** 事件持续不断时，最多等这么久就抓快照。 */
    final long settleMaxMs;
    /** 服务断开后等这么久仍未恢复，以 SERVICE_UNAVAILABLE 结束。 */
    final long serviceGraceMs;

    public EngineConfig(long pollIntervalMs, long quietMs, long settleMaxMs, long serviceGraceMs) {
        this.pollIntervalMs = pollIntervalMs;
        this.quietMs = quietMs;
        this.settleMaxMs = settleMaxMs;
        this.serviceGraceMs = serviceGraceMs;
    }
}

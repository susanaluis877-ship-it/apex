package com.github.susanaluis877.apex.internal.engine;

/**
 * 等界面安静下来再抓快照，避免在页面切换、动画或连续刷新的中途做判断。
 */
public final class Settler {

    private Settler() {
    }

    /**
     * 没有事件时最多等一个轮询间隔；有事件时等到安静 {@code quietMs}，最长 {@code settleMaxMs}。被取消时立即返回。
     */
    public static void settle(Waiter waiter, Clock clock, EngineConfig config) {
        long start = clock.now();
        if (!waiter.awaitEvent(config.pollIntervalMs)) {
            return;
        }
        while (!waiter.isCancelled()) {
            long remaining = config.settleMaxMs - (clock.now() - start);
            if (remaining <= 0 || !waiter.awaitEvent(Math.min(config.quietMs, remaining))) {
                return;
            }
        }
    }
}

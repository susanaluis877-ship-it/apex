package com.github.susanaluis877.apex.internal.engine;

/**
 * 一次运行的取消开关，同时作为引擎的 {@link Waiter}：所有等待都基于全局事件信号，取消时立即唤醒。
 */
public final class RunController implements Waiter {

    private final EventSignal signal;
    private volatile boolean cancelled;

    public RunController(EventSignal signal) {
        this.signal = signal;
    }

    public void cancel() {
        cancelled = true;
        signal.wake();
    }

    @Override
    public boolean isCancelled() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    @Override
    public boolean awaitEvent(long timeoutMs) {
        return signal.await(timeoutMs, this::isCancelled);
    }

    @Override
    public void sleep(long ms) {
        signal.sleep(ms, this::isCancelled);
    }

    @Override
    public void clearEvents() {
        signal.clear();
    }
}

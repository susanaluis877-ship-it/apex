package com.github.susanaluis877.apex.internal.engine;

import java.util.function.BooleanSupplier;

/**
 * 无障碍事件到达的信号。服务线程调用 {@link #notifyEvent()}，引擎线程在 {@link #await} 里等待。
 *
 * <p>只记录“有没有新事件”，不保存事件本身。
 */
public final class EventSignal {

    private final Object lock = new Object();
    private boolean pending;

    public void notifyEvent() {
        synchronized (lock) {
            pending = true;
            lock.notifyAll();
        }
    }

    /** 唤醒所有等待者但不标记事件，用于取消。 */
    public void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public void clear() {
        synchronized (lock) {
            pending = false;
        }
    }

    /**
     * @return 收到事件返回 true 并清除标记；超时或 {@code cancelled} 为真时返回 false
     */
    public boolean await(long timeoutMs, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        synchronized (lock) {
            while (!pending) {
                if (cancelled.getAsBoolean() || !waitUntil(deadline)) {
                    return false;
                }
            }
            if (cancelled.getAsBoolean()) {
                return false;
            }
            pending = false;
            return true;
        }
    }

    /** 休眠到时间结束或 {@code cancelled} 为真，不被事件打断。 */
    public void sleep(long ms, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + ms * 1_000_000;
        synchronized (lock) {
            while (!cancelled.getAsBoolean() && waitUntil(deadline)) {
                // 被事件或取消唤醒后重新检查
            }
        }
    }

    /** 必须持有 lock。到期或被中断返回 false。 */
    private boolean waitUntil(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        try {
            lock.wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

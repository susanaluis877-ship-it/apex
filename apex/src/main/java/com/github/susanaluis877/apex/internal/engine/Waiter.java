package com.github.susanaluis877.apex.internal.engine;

/**
 * 引擎的等待原语。所有等待在流程被取消时都会提前返回。
 */
public interface Waiter {

    /**
     * 等待界面事件。
     *
     * @return 等待期间（或调用前）收到过事件返回 true，并清除事件标记；超时或被取消返回 false
     */
    boolean awaitEvent(long timeoutMs);

    /** 休眠指定时间，不被界面事件打断，只被取消打断。 */
    void sleep(long ms);

    /** 丢弃已累积的事件标记。 */
    void clearEvents();

    boolean isCancelled();
}

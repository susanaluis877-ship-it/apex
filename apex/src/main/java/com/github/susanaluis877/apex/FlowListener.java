package com.github.susanaluis877.apex;

/**
 * 流程运行回调，全部在主线程执行。
 */
public interface FlowListener {

    /**
     * 进入一个状态，动作执行之前回调。
     *
     * @param step 从 1 开始的进入次数
     */
    default void onStateEntered(String stateId, int step) {
    }

    /**
     * 拦截器触发，动作执行之前回调。
     *
     * @param count 该拦截器在本次运行中第几次触发
     */
    default void onInterceptor(String interceptorId, int count) {
    }

    /** 流程结束，每次运行恰好回调一次。回调时运行槽位已经释放，可以在这里直接开始下一个流程。 */
    void onFinished(RunResult result);
}

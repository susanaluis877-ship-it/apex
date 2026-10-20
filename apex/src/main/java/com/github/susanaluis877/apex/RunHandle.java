package com.github.susanaluis877.apex;

/**
 * 一次流程运行的句柄。
 */
public interface RunHandle {

    String getFlowId();

    /** 是否仍在运行。 */
    boolean isRunning();

    /** 请求取消。正在执行的单个设备操作结束后停止，随后以 CANCELLED 回调 onFinished；已结束时无效果。 */
    void cancel();
}

package com.github.susanaluis877.apex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次流程运行的结果。
 */
public final class RunResult {

    public enum Status {
        /** 到达 {@code end: success} 的终止状态。 */
        SUCCESS,
        /** 到达 {@code end: failure} 的终止状态，例如“已售罄”分支。 */
        FAILURE,
        /** 起始状态没出现、下一状态没出现，或整个流程超时。 */
        TIMEOUT,
        /** 进入状态的次数超过 maxSteps，通常是状态之间来回跳转。 */
        MAX_STEPS,
        /** 动作无法执行，例如找不到要启动的 App、目标不是输入框。 */
        ACTION_FAILED,
        /** 调用了 {@link RunHandle#cancel()}。 */
        CANCELLED,
        /** 无障碍服务未开启或运行中断开。 */
        SERVICE_UNAVAILABLE,
        /** 已有流程在运行。 */
        BUSY,
        /** 没有调用 {@link Apex#init}。 */
        NOT_INITIALIZED,
        /** 参数不合法，例如 flow 为 null。 */
        INVALID_ARGUMENT,
        /** 自定义动作或条件抛出异常，或 SDK 内部错误。 */
        ERROR
    }

    private final Status status;
    private final String flowId;
    private final String endStateId;
    private final List<String> path;
    private final Map<String, Integer> interceptorCounts;
    private final long durationMs;
    private final String message;

    public RunResult(Status status, String flowId, String endStateId, List<String> path,
                     Map<String, Integer> interceptorCounts, long durationMs, String message) {
        this.status = status;
        this.flowId = flowId;
        this.endStateId = endStateId;
        this.path = Collections.unmodifiableList(new ArrayList<>(path));
        this.interceptorCounts = Collections.unmodifiableMap(new LinkedHashMap<>(interceptorCounts));
        this.durationMs = durationMs;
        this.message = message;
    }

    /** 没有真正开始运行就结束的结果，例如 BUSY、SERVICE_UNAVAILABLE。 */
    public static RunResult notStarted(Status status, String flowId, String message) {
        return new RunResult(status, flowId, null, Collections.emptyList(), Collections.emptyMap(), 0, message);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public String getFlowId() {
        return flowId;
    }

    /** 结束时所在的状态：终止状态的 id，或超时/失败时停留的状态；还没进入任何状态时为 null。 */
    public String getEndStateId() {
        return endStateId;
    }

    /** 依次进入过的状态 id。 */
    public List<String> getPath() {
        return path;
    }

    /** 每个拦截器触发的次数，没触发过的不在其中。 */
    public Map<String, Integer> getInterceptorCounts() {
        return interceptorCounts;
    }

    public long getDurationMs() {
        return durationMs;
    }

    /** 便于排查问题的说明，例如超时时停留的页面和页面上的文本。 */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "RunResult{status=" + status + ", flow=" + flowId + ", end=" + endStateId + ", path=" + path
                + ", interceptors=" + interceptorCounts + ", durationMs=" + durationMs
                + (message != null ? ", message=" + message : "") + "}";
    }
}

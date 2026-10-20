package com.github.susanaluis877.apex.internal.engine;

/**
 * 单个动作的执行结果。
 */
public final class ActionResult {

    public enum Status {
        OK,
        /** 暂时性失败，例如目标还没出现、手势被打断。引擎稍后续跑或重试。 */
        SOFT_FAIL,
        /** 无法执行，重试也没用，例如没有启动 intent。流程以 ACTION_FAILED 结束。 */
        HARD_FAIL,
        /** 自定义动作抛异常或内部错误。流程以 ERROR 结束。 */
        ERROR,
        CANCELLED
    }

    public static final ActionResult OK = new ActionResult(Status.OK, null);

    private final Status status;
    private final String message;

    private ActionResult(Status status, String message) {
        this.status = status;
        this.message = message;
    }

    public static ActionResult soft(String message) {
        return new ActionResult(Status.SOFT_FAIL, message);
    }

    public static ActionResult hard(String message) {
        return new ActionResult(Status.HARD_FAIL, message);
    }

    public static ActionResult error(String message) {
        return new ActionResult(Status.ERROR, message);
    }

    public static ActionResult cancelled() {
        return new ActionResult(Status.CANCELLED, "cancelled");
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return message == null ? status.name() : status + "(" + message + ")";
    }
}

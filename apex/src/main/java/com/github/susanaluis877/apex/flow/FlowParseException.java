package com.github.susanaluis877.apex.flow;

/**
 * JSON 流程定义解析失败。{@link #getPath()} 指出出错的位置，例如 {@code states[1].next[0]}。
 */
public class FlowParseException extends Exception {

    private final String path;
    private final String reason;

    FlowParseException(String path, String reason) {
        this(path, reason, null);
    }

    FlowParseException(String path, String reason, Throwable cause) {
        super((path.isEmpty() ? "(root)" : path) + ": " + reason, cause);
        this.path = path;
        this.reason = reason;
    }

    /** 出错位置的 JSON 路径，根节点为空字符串。 */
    public String getPath() {
        return path;
    }

    /** 不带路径的错误原因。 */
    public String getReason() {
        return reason;
    }
}

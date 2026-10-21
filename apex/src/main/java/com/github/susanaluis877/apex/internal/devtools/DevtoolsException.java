package com.github.susanaluis877.apex.internal.devtools;

/**
 * devtools 请求失败，{@link #getCode()} 原样返回给客户端，用来区分可重试和需要修改请求的错误。
 */
public final class DevtoolsException extends Exception {

    /** 请求格式或参数不对。 */
    public static final String BAD_REQUEST = "BAD_REQUEST";
    /** 流程、选择器或动作的 JSON 解析失败，响应里带 JSON 路径。 */
    public static final String PARSE_ERROR = "PARSE_ERROR";
    /** 流程定义不合法（Java DSL 构建时校验失败），或者引用的流程类、方法找不到。 */
    public static final String INVALID_FLOW = "INVALID_FLOW";
    /** 流程里有内联代码，不能写成 JSON。 */
    public static final String NOT_EXPORTABLE = "NOT_EXPORTABLE";
    /** 有流程正在运行。 */
    public static final String BUSY = "BUSY";
    /** 无障碍服务没有连接。 */
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    /** 请求引用的快照已经不是最新的，需要重新抓取。 */
    public static final String STALE_SNAPSHOT = "STALE_SNAPSHOT";
    /** 设备上的操作超时。 */
    public static final String TIMEOUT = "TIMEOUT";
    public static final String INTERNAL = "INTERNAL";

    private final String code;
    private final String path;

    public DevtoolsException(String code, String message) {
        this(code, message, null);
    }

    public DevtoolsException(String code, String message, String path) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public String getCode() {
        return code;
    }

    /** {@link #PARSE_ERROR} 时出错位置的 JSON 路径，其他情况为 null。 */
    public String getPath() {
        return path;
    }
}

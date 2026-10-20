package com.github.susanaluis877.apex;

import com.github.susanaluis877.apex.internal.LogcatLogger;

/**
 * SDK 日志的接收器。通过 {@link ApexConfig.Builder#setLogger} 设置后，SDK 的所有日志都交给它，不再直接打印到 logcat，
 * 宿主可以写文件、接 Timber 或上报。需要同时保留 logcat 时，在实现里再调用一次 {@link #LOGCAT}。
 *
 * <p>可能在任意线程调用（主要是引擎线程），实现需要线程安全且尽快返回；抛出的异常会被忽略。
 */
@FunctionalInterface
public interface ApexLogger {

    /** 级别取值与 {@code android.util.Log} 相同。 */
    int VERBOSE = 2;
    int DEBUG = 3;
    int INFO = 4;
    int WARN = 5;
    int ERROR = 6;

    /** 默认实现：输出到 logcat，长消息按行和 4000 字符拆分，避免被截断。 */
    ApexLogger LOGCAT = LogcatLogger.INSTANCE;

    /**
     * @param priority  {@link #VERBOSE} ~ {@link #ERROR}
     * @param tag       固定为 {@code "Apex"}
     * @param message   日志内容，可能包含多行（例如失败时的节点树）
     * @param throwable 可能为 null
     */
    void log(int priority, String tag, String message, Throwable throwable);
}

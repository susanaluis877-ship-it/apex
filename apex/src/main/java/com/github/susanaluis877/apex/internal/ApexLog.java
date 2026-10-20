package com.github.susanaluis877.apex.internal;

import com.github.susanaluis877.apex.ApexLogger;

/**
 * SDK 统一日志入口。按级别过滤后交给 {@link ApexLogger}，默认输出到 logcat。
 *
 * <p>没开 debug 时默认只输出 WARN 及以上；运行轨迹另外完整记录在 {@code RunResult} 里，不受级别影响。
 */
public final class ApexLog {

    public static final String TAG = "Apex";

    private static volatile ApexLogger logger = ApexLogger.LOGCAT;
    private static volatile int level = ApexLogger.WARN;

    private ApexLog() {
    }

    public static void configure(ApexLogger newLogger, int newLevel) {
        logger = newLogger != null ? newLogger : ApexLogger.LOGCAT;
        level = newLevel;
    }

    public static boolean isLoggable(int priority) {
        return priority >= level;
    }

    public static void log(int priority, String msg, Throwable t) {
        if (priority < level) {
            return;
        }
        try {
            logger.log(priority, TAG, msg, t);
        } catch (RuntimeException ignored) {
            // 宿主的日志实现出错不能影响 SDK
        }
    }

    public static void v(String msg) {
        log(ApexLogger.VERBOSE, msg, null);
    }

    public static void d(String msg) {
        log(ApexLogger.DEBUG, msg, null);
    }

    public static void i(String msg) {
        log(ApexLogger.INFO, msg, null);
    }

    public static void w(String msg) {
        log(ApexLogger.WARN, msg, null);
    }

    public static void e(String msg) {
        log(ApexLogger.ERROR, msg, null);
    }

    public static void e(String msg, Throwable t) {
        log(ApexLogger.ERROR, msg, t);
    }
}

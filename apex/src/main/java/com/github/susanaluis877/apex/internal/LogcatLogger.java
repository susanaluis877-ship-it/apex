package com.github.susanaluis877.apex.internal;

import android.util.Log;

import com.github.susanaluis877.apex.ApexLogger;

/**
 * 输出到 logcat。logcat 单条日志超过约 4000 字节会被截断，所以按行、再按长度拆分。
 */
public final class LogcatLogger implements ApexLogger {

    public static final LogcatLogger INSTANCE = new LogcatLogger();

    private static final int MAX_CHUNK = 4000;

    private LogcatLogger() {
    }

    @Override
    public void log(int priority, String tag, String message, Throwable throwable) {
        String text = throwable == null ? message : message + "\n" + Log.getStackTraceString(throwable);
        for (String line : text.split("\n")) {
            for (int start = 0; start < line.length(); start += MAX_CHUNK) {
                Log.println(priority, tag, line.substring(start, Math.min(line.length(), start + MAX_CHUNK)));
            }
        }
    }
}

package com.example.apex.internal;

import android.util.Log;

/**
 * SDK 统一日志。调试日志默认关闭，错误日志始终输出。
 */
public final class ApexLog {

    private static final String TAG = "Apex";

    private static volatile boolean debug;

    private ApexLog() {
    }

    static void setDebug(boolean enabled) {
        debug = enabled;
    }

    static void d(String msg) {
        if (debug) {
            Log.d(TAG, msg);
        }
    }

    static void i(String msg) {
        if (debug) {
            Log.i(TAG, msg);
        }
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }
}

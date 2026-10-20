package com.example.apex.internal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.apex.ApexCallback;
import com.example.apex.ApexConfig;
import com.example.apex.BuildConfig;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * SDK 内部实现，不属于公开 API，宿主不应直接调用。
 */
public final class ApexCore {

    private static final ApexCore INSTANCE = new ApexCore();

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context appContext;
    private Executor executor;
    // volatile 写在其他字段赋值之后，读到 true 时其他字段一定可见
    private volatile boolean initialized;

    private ApexCore() {
    }

    public static ApexCore get() {
        return INSTANCE;
    }

    public void init(Context appContext, ApexConfig config) {
        synchronized (lock) {
            if (initialized) {
                ApexLog.d("init: already initialized, ignored");
                return;
            }
            ApexLog.setDebug(config.isDebug());
            this.appContext = appContext;
            Executor injected = config.getExecutor();
            this.executor = injected != null ? injected : Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "apex-worker");
                thread.setDaemon(true);
                return thread;
            });
            initialized = true;
        }
        ApexLog.i("initialized, version=" + BuildConfig.SDK_VERSION
                + ", host=" + appContext.getPackageName());
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void ping(ApexCallback callback) {
        if (!initialized) {
            ApexLog.e("ping: Apex.init() not called");
            mainHandler.post(() -> callback.onError(
                    ApexCallback.ERROR_NOT_INITIALIZED, "Apex.init() not called"));
            return;
        }
        try {
            executor.execute(() -> {
                String result = "pong from " + appContext.getPackageName();
                ApexLog.d("ping: " + result);
                mainHandler.post(() -> callback.onSuccess(result));
            });
        } catch (Throwable t) {
            // 宿主注入的线程池可能已关闭
            ApexLog.e("ping: executor rejected", t);
            mainHandler.post(() -> callback.onError(ApexCallback.ERROR_INTERNAL, String.valueOf(t)));
        }
    }
}

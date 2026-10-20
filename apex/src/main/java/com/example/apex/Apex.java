package com.example.apex;

import android.content.Context;

import com.example.apex.internal.ApexCore;
import com.example.apex.internal.ApexLog;

/**
 * Apex SDK 对外入口。
 *
 * <p>所有公开方法都不会向宿主抛出异常，内部错误只记录日志或通过回调返回。
 */
public final class Apex {

    private Apex() {
    }

    /**
     * 初始化 SDK。建议在宿主 {@code Application.onCreate()} 中调用。
     * 可重复调用，只有第一次生效。
     *
     * @param context 任意 Context，SDK 内部只持有 ApplicationContext
     * @param config  SDK 配置，传 null 时使用默认配置
     */
    public static void init(Context context, ApexConfig config) {
        if (context == null) {
            ApexLog.e("init: context is null");
            return;
        }
        try {
            Context appContext = context.getApplicationContext();
            ApexCore.get().init(
                    appContext != null ? appContext : context,
                    config != null ? config : new ApexConfig.Builder().build());
        } catch (Throwable t) {
            ApexLog.e("init failed", t);
        }
    }

    /**
     * SDK 是否已初始化。
     */
    public static boolean isInitialized() {
        return ApexCore.get().isInitialized();
    }

    /**
     * 异步执行一次示例任务，结果在主线程回调。
     */
    public static void ping(ApexCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            ApexCore.get().ping(callback);
        } catch (Throwable t) {
            ApexLog.e("ping failed", t);
        }
    }

    /**
     * SDK 版本号，便于宿主上报和排查问题。
     */
    public static String getVersion() {
        return BuildConfig.SDK_VERSION;
    }
}

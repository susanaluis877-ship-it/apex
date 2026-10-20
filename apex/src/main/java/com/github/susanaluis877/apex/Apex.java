package com.github.susanaluis877.apex;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.github.susanaluis877.apex.action.CustomAction;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.internal.ApexCore;
import com.github.susanaluis877.apex.internal.ApexLog;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.match.CustomMatcher;
import com.github.susanaluis877.apex.service.ApexAccessibilityService;

/**
 * Apex SDK 对外入口。
 *
 * <p>所有方法都不会向宿主抛出异常，内部错误只记录日志或通过回调返回。
 * 定义流程的 {@link Flow.Builder#build()} 和 {@link com.github.susanaluis877.apex.flow.FlowParser#parse}
 * 是例外：流程写错时会抛出带具体位置的异常。
 */
public final class Apex {

    private Apex() {
    }

    /**
     * 初始化 SDK。建议在宿主 {@code Application.onCreate()} 中调用。
     * 可重复调用，只有第一次生效。
     *
     * @param context 任意 Context，SDK 不持有它
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
     * 用户是否在系统设置里开启了本 App 的无障碍服务。开启后服务可能还要一小段时间才连接上。
     */
    public static boolean isServiceEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                return false;
            }
            ComponentName self = new ComponentName(context, ApexAccessibilityService.class);
            for (String item : enabled.split(":")) {
                if (self.equals(ComponentName.unflattenFromString(item))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            ApexLog.e("isServiceEnabled failed", t);
        }
        return false;
    }

    /**
     * 无障碍服务是否已连接，连接后才能运行流程。
     */
    public static boolean isServiceConnected() {
        return ApexCore.get().isServiceConnected();
    }

    /**
     * 打开系统无障碍设置页，引导用户开启服务。
     *
     * @return 是否成功打开
     */
    public static boolean openAccessibilitySettings(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            ApexLog.e("openAccessibilitySettings failed", t);
            return false;
        }
    }

    /**
     * 注册自定义动作，流程里通过名字引用。必须在构建或解析引用它的流程之前注册；同名覆盖。
     */
    public static void registerAction(String name, CustomAction action) {
        if (name == null || action == null) {
            ApexLog.e("registerAction: name and action must not be null");
            return;
        }
        Registry.get().registerAction(name, action);
    }

    /**
     * 注册自定义页面条件，流程里通过名字引用。必须在构建或解析引用它的流程之前注册；同名覆盖。
     */
    public static void registerMatcher(String name, CustomMatcher matcher) {
        if (name == null || matcher == null) {
            ApexLog.e("registerMatcher: name and matcher must not be null");
            return;
        }
        Registry.get().registerMatcher(name, matcher);
    }

    /**
     * 开始运行一个流程。同一时间只能运行一个流程。
     *
     * <p>无法开始时（未初始化、服务未连接、已有流程在运行等）也会回调 {@link FlowListener#onFinished}，
     * 状态分别为 NOT_INITIALIZED、SERVICE_UNAVAILABLE、BUSY 等。所有回调都在主线程。
     *
     * @param listener 可以为 null
     * @return 用来取消运行的句柄，不会为 null
     */
    public static RunHandle run(Flow flow, FlowListener listener) {
        try {
            return ApexCore.get().run(flow, listener);
        } catch (Throwable t) {
            ApexLog.e("run failed", t);
            String flowId = flow != null ? flow.getId() : null;
            return new RunHandle() {
                @Override
                public String getFlowId() {
                    return flowId;
                }

                @Override
                public boolean isRunning() {
                    return false;
                }

                @Override
                public void cancel() {
                }
            };
        }
    }

    /**
     * 当前是否有流程在运行。
     */
    public static boolean isRunning() {
        return ApexCore.get().isRunning();
    }

    /**
     * 抓取当前屏幕并打印节点树（debug 模式下同时输出到 logcat），用来编写选择器。有流程在运行时不可用。
     */
    public static void dumpScreen(DumpCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            ApexCore.get().dumpScreen(callback);
        } catch (Throwable t) {
            ApexLog.e("dumpScreen failed", t);
        }
    }

    /**
     * SDK 版本号，便于宿主上报和排查问题。
     */
    public static String getVersion() {
        return BuildConfig.SDK_VERSION;
    }
}

package com.github.susanaluis877.apex.internal.devtools;

import com.github.susanaluis877.apex.RunHandle;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.internal.engine.RunMonitor;
import com.github.susanaluis877.apex.internal.engine.Waiter;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.Consumer;

/**
 * {@link Dispatcher} 依赖的平台能力。Android 上由 {@code DevBridge} 实现，测试里用假设备实现。
 */
public interface Backend {

    /** 平台相关的基本信息：SDK 版本、包名、服务状态、是否在运行流程等。 */
    JSONObject info() throws JSONException;

    /**
     * 在引擎线程上执行一个 devtools 任务。
     *
     * @throws DevtoolsException 有流程在运行时为 {@link DevtoolsException#BUSY}，超时为 {@link DevtoolsException#TIMEOUT}，
     *                           任务自己抛出的 DevtoolsException 原样抛出，其他异常包装成 {@link DevtoolsException#INTERNAL}
     */
    <T> T call(Task<T> task, long timeoutMs) throws DevtoolsException;

    /**
     * 按正常的运行规则启动流程（同一时间只能跑一个）。没能启动时也会调用 {@code onFinished}。
     *
     * @param sink       每条轨迹事件，在引擎线程上调用
     * @param onFinished 恰好调用一次，线程不确定
     */
    RunHandle run(Flow flow, Consumer<TraceEvent> sink, Consumer<RunResult> onFinished);

    /** 取消正在运行的流程；没有流程在运行时返回 false。 */
    boolean cancelRunning();

    /** 宿主代码的类加载器，用来按 {@code flowRef} 加载 Java DSL 写的流程。 */
    ClassLoader classLoader();

    /** 当前或最近一次运行的状态，宿主代码发起的运行也在里面。 */
    RunMonitor monitor();

    /**
     * 暂时隐藏或恢复状态悬浮窗，电脑端截图前后调用。隐藏后过几秒会自动恢复，防止客户端中途断开后一直不显示。
     *
     * @return 有没有悬浮窗
     */
    boolean setOverlayVisible(boolean visible);

    interface Task<T> {
        T run(DevtoolsSession session, Waiter waiter) throws Exception;
    }
}

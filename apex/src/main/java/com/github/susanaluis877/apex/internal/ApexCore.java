package com.github.susanaluis877.apex.internal;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import com.github.susanaluis877.apex.ApexConfig;
import com.github.susanaluis877.apex.BuildConfig;
import com.github.susanaluis877.apex.DumpCallback;
import com.github.susanaluis877.apex.FlowListener;
import com.github.susanaluis877.apex.RunHandle;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.internal.android.AccessibilityScreenSource;
import com.github.susanaluis877.apex.internal.android.ActivityTracker;
import com.github.susanaluis877.apex.internal.android.AndroidActionExecutor;
import com.github.susanaluis877.apex.internal.android.StatusOverlay;
import com.github.susanaluis877.apex.internal.engine.Clock;
import com.github.susanaluis877.apex.internal.engine.EngineConfig;
import com.github.susanaluis877.apex.internal.engine.EventSignal;
import com.github.susanaluis877.apex.internal.engine.FlowEngine;
import com.github.susanaluis877.apex.internal.engine.RunController;
import com.github.susanaluis877.apex.internal.engine.RunMonitor;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.service.ApexAccessibilityService;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SDK 内部实现，不属于公开 API，宿主不应直接调用。
 *
 * <p>线程模型：所有节点查询和动作都在唯一一条常驻的 {@code apex-engine} 线程上执行。
 * 不能每次运行新建线程：系统按线程缓存无障碍连接对象且不释放，换线程就会泄漏。
 */
public final class ApexCore {

    private static final ApexCore INSTANCE = new ApexCore();

    private final Object lock = new Object();
    private final EventSignal signal = new EventSignal();
    private final ActivityTracker activityTracker = new ActivityTracker();
    private final AtomicReference<Run> current = new AtomicReference<>();
    private final AtomicInteger runCounter = new AtomicInteger();
    private final RunMonitor monitor = new RunMonitor();

    private Handler mainHandler;
    private ExecutorService engineThread;
    // volatile 写在其他字段赋值之后，读到 true 时其他字段一定可见
    private volatile boolean initialized;
    private volatile ApexAccessibilityService service;
    private volatile Context appContext;
    private volatile boolean overlayEnabled;
    /** 只在主线程上创建和移除。 */
    private volatile StatusOverlay overlay;

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
            ApexLog.configure(config.getLogger(), config.getLogLevel());
            this.appContext = appContext;
            overlayEnabled = config.isStatusOverlay();
            initialized = true;
        }
        if (overlayEnabled && service != null) {
            // 服务比 init 先连上（宿主在 Activity 里才 init）
            postMain(this::showOverlay);
        }
        ApexLog.i("initialized, version=" + BuildConfig.SDK_VERSION + ", host=" + appContext.getPackageName()
                + ", service connected=" + isServiceConnected());
        try {
            DevBridge.maybeStart(appContext, config);
        } catch (Throwable t) {
            // 调试桥只是开发辅助，任何问题都不能影响宿主
            ApexLog.w("devtools bridge not started: " + t);
        }
    }

    /** {@link #init} 时传入的 application context，未初始化时为 null。 */
    Context appContext() {
        return appContext;
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ---- 服务 ----

    /** 在主线程调用。 */
    public void onServiceConnected(ApexAccessibilityService svc) {
        service = svc;
        ApexLog.i("accessibility service connected");
        if (overlayEnabled) {
            showOverlay();
        }
    }

    /** 在主线程调用。 */
    public void onServiceDisconnected(ApexAccessibilityService svc) {
        if (service == svc) {
            service = null;
            removeOverlay();
            signal.wake();
            ApexLog.i("accessibility service disconnected");
        }
    }

    public void onAccessibilityEvent(int type, CharSequence packageName, CharSequence className) {
        if (className != null && StatusOverlay.A11Y_CLASS.contentEquals(className)) {
            // 状态悬浮窗自己刷新产生的事件，页面没有变化
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            activityTracker.onWindowStateChanged(packageName, className);
        }
        signal.notifyEvent();
    }

    public boolean isServiceConnected() {
        return service != null;
    }

    private AccessibilityService service() {
        return service;
    }

    /** 新建一个基于当前服务的屏幕源，只能在引擎线程上使用。 */
    AccessibilityScreenSource newScreenSource() {
        return new AccessibilityScreenSource(this::service, activityTracker);
    }

    AndroidActionExecutor newExecutor(AccessibilityScreenSource source) {
        return new AndroidActionExecutor(source, this::service, () -> overlay);
    }

    // ---- 状态悬浮窗 ----

    private void showOverlay() {
        ApexAccessibilityService svc = service;
        if (overlay != null || svc == null) {
            return;
        }
        StatusOverlay o = new StatusOverlay(svc, monitor, this::cancelCurrent);
        o.attach();
        overlay = o;
    }

    private void removeOverlay() {
        StatusOverlay o = overlay;
        overlay = null;
        if (o != null) {
            o.detach();
        }
    }

    /** 暂时隐藏或恢复悬浮窗（调试桥截图用），返回有没有悬浮窗。可以在任意线程调用。 */
    boolean setOverlayVisible(boolean visible) {
        StatusOverlay o = overlay;
        if (o == null) {
            return false;
        }
        o.setVisible(visible);
        return true;
    }

    /** 新的取消开关，等待基于全局事件信号。 */
    RunController newController() {
        return new RunController(signal);
    }

    /** 在引擎线程上执行，供调试桥使用。 */
    <T> Future<T> submit(Callable<T> task) {
        return engineThread().submit(task);
    }

    // ---- 运行 ----

    public boolean isRunning() {
        return current.get() != null;
    }

    /** 当前或最近一次运行的状态，宿主代码和调试桥发起的运行都在里面。 */
    RunMonitor monitor() {
        return monitor;
    }

    /** 正在运行的流程 id，没有时为 null。 */
    String runningFlowId() {
        Run run = current.get();
        return run != null ? run.flowId : null;
    }

    /** 取消正在运行的流程；没有流程在运行时返回 false。 */
    boolean cancelCurrent() {
        Run run = current.get();
        if (run == null) {
            return false;
        }
        run.cancel();
        return true;
    }

    public RunHandle run(Flow flow, FlowListener listener) {
        return run(flow, listener, null);
    }

    /**
     * @param traceSink 每条轨迹事件产生时在引擎线程上回调，可以为 null
     */
    RunHandle run(Flow flow, FlowListener listener, Consumer<TraceEvent> traceSink) {
        String flowId = flow != null ? flow.getId() : null;
        if (flow == null) {
            return finishImmediately(listener, RunResult.notStarted(RunResult.Status.INVALID_ARGUMENT, null,
                    "flow is null"));
        }
        if (!initialized) {
            return finishImmediately(listener, RunResult.notStarted(RunResult.Status.NOT_INITIALIZED, flowId,
                    "Apex.init() not called"));
        }
        if (service == null) {
            return finishImmediately(listener, RunResult.notStarted(RunResult.Status.SERVICE_UNAVAILABLE, flowId,
                    "accessibility service is not connected; ask the user to enable it in system settings"));
        }
        Run run = new Run(flowId, new RunController(signal));
        String runId = flowId + "#" + runCounter.incrementAndGet();
        if (!current.compareAndSet(null, run)) {
            return finishImmediately(listener, RunResult.notStarted(RunResult.Status.BUSY, flowId,
                    "flow '" + current.get() + "' is running"));
        }
        FlowListener engineListener = new MonitoringListener(listener != null ? new MainThreadListener(listener) : null);
        engineThread().execute(() -> {
            RunResult result;
            monitor.onStart(runId, flowId);
            try {
                AccessibilityScreenSource source = newScreenSource();
                FlowEngine engine = new FlowEngine(runId, flow, Registry.get().globalInterceptors(), source,
                        newExecutor(source), Clock.SYSTEM, run.controller, engineListener, EngineConfig.DEFAULT);
                engine.setTraceSink(event -> {
                    monitor.onTrace(event);
                    if (traceSink != null) {
                        traceSink.accept(event);
                    }
                });
                result = engine.run();
            } catch (Throwable t) {
                ApexLog.e("[" + runId + "] run crashed", t);
                result = RunResult.notStarted(RunResult.Status.ERROR, flowId, "internal error: " + t);
            }
            monitor.onFinish(result);
            // 先释放槽位再回调，宿主可以在 onFinished 里直接开始下一个流程
            run.finished = true;
            current.compareAndSet(run, null);
            if (listener != null) {
                RunResult finalResult = result;
                postMain(() -> listener.onFinished(finalResult));
            }
        });
        return run;
    }

    public void dumpScreen(DumpCallback callback) {
        if (service == null) {
            postMain(() -> callback.onDump(null, "unavailable: accessibility service is not connected"));
            return;
        }
        if (isRunning()) {
            postMain(() -> callback.onDump(null, "unavailable: a flow is running"));
            return;
        }
        engineThread().execute(() -> {
            ScreenSnapshot snapshot = newScreenSource().snapshot();
            String text = snapshot != null ? ScreenDumper.dump(snapshot) : "unavailable: snapshot failed";
            ApexLog.i(text);
            postMain(() -> callback.onDump(snapshot, text));
        });
    }

    private RunHandle finishImmediately(FlowListener listener, RunResult result) {
        ApexLog.w("run not started: " + result);
        if (listener != null) {
            postMain(() -> listener.onFinished(result));
        }
        Run run = new Run(result.getFlowId(), new RunController(signal));
        run.finished = true;
        return run;
    }

    private ExecutorService engineThread() {
        synchronized (lock) {
            if (engineThread == null) {
                engineThread = Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "apex-engine");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return engineThread;
        }
    }

    private void postMain(Runnable r) {
        Handler handler;
        synchronized (lock) {
            if (mainHandler == null) {
                mainHandler = new Handler(Looper.getMainLooper());
            }
            handler = mainHandler;
        }
        handler.post(r);
    }

    private static final class Run implements RunHandle {
        final String flowId;
        final RunController controller;
        volatile boolean finished;

        Run(String flowId, RunController controller) {
            this.flowId = flowId;
            this.controller = controller;
        }

        @Override
        public String getFlowId() {
            return flowId;
        }

        @Override
        public boolean isRunning() {
            return !finished;
        }

        @Override
        public void cancel() {
            if (!finished) {
                controller.cancel();
            }
        }

        @Override
        public String toString() {
            return flowId;
        }
    }

    /** 在引擎线程上先记进 {@link RunMonitor}，再交给宿主的监听器（可以为 null）。 */
    private final class MonitoringListener implements FlowListener {
        private final FlowListener delegate;

        MonitoringListener(FlowListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onStateEntered(String stateId, int step) {
            monitor.onStateEntered(stateId, step);
            if (delegate != null) {
                delegate.onStateEntered(stateId, step);
            }
        }

        @Override
        public void onInterceptor(String interceptorId, int count) {
            if (delegate != null) {
                delegate.onInterceptor(interceptorId, count);
            }
        }

        @Override
        public void onFinished(RunResult result) {
            // 引擎不调用；结果由 run() 的返回值处理
        }
    }

    /** 把引擎线程上的进度回调转到主线程。 */
    private final class MainThreadListener implements FlowListener {
        private final FlowListener delegate;

        MainThreadListener(FlowListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onStateEntered(String stateId, int step) {
            postMain(() -> delegate.onStateEntered(stateId, step));
        }

        @Override
        public void onInterceptor(String interceptorId, int count) {
            postMain(() -> delegate.onInterceptor(interceptorId, count));
        }

        @Override
        public void onFinished(RunResult result) {
            postMain(() -> delegate.onFinished(result));
        }
    }
}

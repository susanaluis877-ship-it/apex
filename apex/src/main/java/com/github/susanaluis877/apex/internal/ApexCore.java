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
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.internal.android.AccessibilityScreenSource;
import com.github.susanaluis877.apex.internal.android.ActivityTracker;
import com.github.susanaluis877.apex.internal.android.AndroidActionExecutor;
import com.github.susanaluis877.apex.internal.engine.Clock;
import com.github.susanaluis877.apex.internal.engine.EngineConfig;
import com.github.susanaluis877.apex.internal.engine.EventSignal;
import com.github.susanaluis877.apex.internal.engine.FlowEngine;
import com.github.susanaluis877.apex.internal.engine.RunController;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.service.ApexAccessibilityService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    private Handler mainHandler;
    private ExecutorService engineThread;
    // volatile 写在其他字段赋值之后，读到 true 时其他字段一定可见
    private volatile boolean initialized;
    private volatile ApexAccessibilityService service;

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
            initialized = true;
        }
        ApexLog.i("initialized, version=" + BuildConfig.SDK_VERSION + ", host=" + appContext.getPackageName()
                + ", service connected=" + isServiceConnected());
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ---- 服务 ----

    public void onServiceConnected(ApexAccessibilityService svc) {
        service = svc;
        ApexLog.i("accessibility service connected");
    }

    public void onServiceDisconnected(ApexAccessibilityService svc) {
        if (service == svc) {
            service = null;
            signal.wake();
            ApexLog.i("accessibility service disconnected");
        }
    }

    public void onAccessibilityEvent(int type, CharSequence packageName, CharSequence className) {
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

    // ---- 运行 ----

    public boolean isRunning() {
        return current.get() != null;
    }

    public RunHandle run(Flow flow, FlowListener listener) {
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
        FlowListener mainListener = listener != null ? new MainThreadListener(listener) : null;
        engineThread().execute(() -> {
            RunResult result;
            try {
                AccessibilityScreenSource source = new AccessibilityScreenSource(this::service, activityTracker);
                AndroidActionExecutor executor = new AndroidActionExecutor(source, this::service);
                result = new FlowEngine(runId, flow, source, executor, Clock.SYSTEM, run.controller,
                        mainListener, EngineConfig.DEFAULT).run();
            } catch (Throwable t) {
                ApexLog.e("[" + runId + "] run crashed", t);
                result = RunResult.notStarted(RunResult.Status.ERROR, flowId, "internal error: " + t);
            }
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
            ScreenSnapshot snapshot = new AccessibilityScreenSource(this::service, activityTracker).snapshot();
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

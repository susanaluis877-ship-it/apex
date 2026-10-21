package com.github.susanaluis877.apex.internal;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Process;

import com.github.susanaluis877.apex.Apex;
import com.github.susanaluis877.apex.ApexConfig;
import com.github.susanaluis877.apex.BuildConfig;
import com.github.susanaluis877.apex.FlowListener;
import com.github.susanaluis877.apex.RunHandle;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.internal.android.AccessibilityScreenSource;
import com.github.susanaluis877.apex.internal.devtools.Backend;
import com.github.susanaluis877.apex.internal.devtools.DevtoolsException;
import com.github.susanaluis877.apex.internal.devtools.DevtoolsSession;
import com.github.susanaluis877.apex.internal.devtools.Dispatcher;
import com.github.susanaluis877.apex.internal.engine.Clock;
import com.github.susanaluis877.apex.internal.engine.EngineConfig;
import com.github.susanaluis877.apex.internal.engine.RunController;
import com.github.susanaluis877.apex.internal.engine.RunMonitor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 调试桥：在抽象命名空间的 Unix socket {@code apex_<包名>} 上提供 devtools 协议（见 {@link Dispatcher}），
 * 电脑上通过 {@code adb forward tcp:N localabstract:apex_<包名>} 连接。
 *
 * <ul>
 *   <li>只在 debuggable 包的主进程里、且 {@link ApexConfig#isDevtools()} 为 true 时启动。
 *       debuggable 包本来就允许 {@code run-as}，所以不扩大可访问范围；release 包里永远不启动。</li>
 *   <li>Unix socket 不需要 INTERNET 权限，也不需要在 manifest 里声明任何东西。</li>
 *   <li>只接受 root、shell（adb）和本应用的连接：Android 7~8 上其他应用也能连到抽象 socket。</li>
 *   <li>每个连接一个守护线程，读一行请求、写完响应就关闭。屏幕操作都提交到引擎线程。</li>
 * </ul>
 */
final class DevBridge implements Backend {

    /** 单行请求的上限，足够放下几十份页面样本。 */
    private static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    private static final int SHELL_UID = 2000;

    private final ApexCore core;
    private final Context context;
    private final String socketName;
    private final Dispatcher dispatcher;
    /** 只在引擎线程上访问。 */
    private DevtoolsSession session;

    private DevBridge(ApexCore core, Context context) {
        this.core = core;
        this.context = context;
        this.socketName = socketName(context.getPackageName());
        this.dispatcher = new Dispatcher(this);
    }

    static String socketName(String packageName) {
        return "apex_" + packageName;
    }

    static void maybeStart(Context context, ApexConfig config) {
        if (!config.isDevtools()) {
            return;
        }
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return;
        }
        String process = processName();
        if (process != null && !process.equals(context.getPackageName())) {
            ApexLog.d("devtools bridge: skipped in process " + process);
            return;
        }
        new DevBridge(ApexCore.get(), context).start();
    }

    private void start() {
        Thread thread = new Thread(this::serve, "apex-devtools");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve() {
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(socketName);
        } catch (IOException e) {
            ApexLog.w("devtools bridge: cannot listen on @" + socketName + ": " + e);
            return;
        }
        ApexLog.i("devtools bridge listening on @" + socketName
                + " (adb forward tcp:<port> localabstract:" + socketName + ")");
        while (true) {
            LocalSocket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                ApexLog.w("devtools bridge stopped: " + e);
                return;
            }
            Thread worker = new Thread(() -> handle(socket), "apex-devtools-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(LocalSocket socket) {
        try {
            Credentials peer = socket.getPeerCredentials();
            int uid = peer.getUid();
            if (uid != 0 && uid != SHELL_UID && uid != Process.myUid()) {
                ApexLog.w("devtools bridge: rejected connection from uid " + uid);
                return;
            }
            String line = readLine(socket.getInputStream());
            if (line == null) {
                return;
            }
            OutputStream out = socket.getOutputStream();
            dispatcher.handle(line, response -> {
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            });
        } catch (IOException e) {
            ApexLog.d("devtools connection closed: " + e);
        } catch (Throwable t) {
            ApexLog.e("devtools connection failed", t);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已经断开
            }
        }
    }

    /** 读到换行或连接结束为止；超过上限时抛异常。没有任何数据时返回 null。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (true) {
            int n = in.read(chunk);
            if (n < 0) {
                break;
            }
            int newline = -1;
            for (int i = 0; i < n; i++) {
                if (chunk[i] == '\n') {
                    newline = i;
                    break;
                }
            }
            buf.write(chunk, 0, newline >= 0 ? newline : n);
            if (buf.size() > MAX_REQUEST_BYTES) {
                throw new IOException("request larger than " + MAX_REQUEST_BYTES + " bytes");
            }
            if (newline >= 0) {
                break;
            }
        }
        return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        try (InputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] bytes = new byte[256];
            int n = in.read(bytes);
            int end = 0;
            while (end < n && bytes[end] != 0) {
                end++;
            }
            return end > 0 ? new String(bytes, 0, end, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---- Backend ----

    @Override
    public JSONObject info() throws JSONException {
        return new JSONObject()
                .put("sdkVersion", BuildConfig.SDK_VERSION)
                .put("package", context.getPackageName())
                .put("socket", socketName)
                .put("api", Build.VERSION.SDK_INT)
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("initialized", core.isInitialized())
                .put("serviceEnabled", Apex.isServiceEnabled(context))
                .put("serviceConnected", core.isServiceConnected())
                .putOpt("running", core.runningFlowId());
    }

    @Override
    public <T> T call(Task<T> task, long timeoutMs) throws DevtoolsException {
        if (core.isRunning()) {
            throw new DevtoolsException(DevtoolsException.BUSY, "flow '" + core.runningFlowId()
                    + "' is running; wait for it or send {\"cmd\": \"cancel\"}");
        }
        RunController waiter = core.newController();
        Future<T> future = core.submit(() -> task.run(session(), waiter));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            waiter.cancel();
            throw new DevtoolsException(DevtoolsException.TIMEOUT, "no result within " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            waiter.cancel();
            Thread.currentThread().interrupt();
            throw new DevtoolsException(DevtoolsException.INTERNAL, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DevtoolsException) {
                throw (DevtoolsException) cause;
            }
            ApexLog.e("devtools task failed", cause);
            throw new DevtoolsException(DevtoolsException.INTERNAL, String.valueOf(cause));
        }
    }

    /** 整个调试桥共用一个屏幕源，快照序号单调递增。只在引擎线程上调用。 */
    private DevtoolsSession session() {
        if (session == null) {
            AccessibilityScreenSource source = core.newScreenSource();
            session = new DevtoolsSession(source, core.newExecutor(source), Clock.SYSTEM, EngineConfig.DEFAULT);
        }
        return session;
    }

    @Override
    public RunHandle run(Flow flow, Consumer<TraceEvent> sink, Consumer<RunResult> onFinished) {
        return core.run(flow, new FlowListener() {
            @Override
            public void onFinished(RunResult result) {
                onFinished.accept(result);
            }
        }, sink);
    }

    @Override
    public boolean cancelRunning() {
        return core.cancelCurrent();
    }

    @Override
    public ClassLoader classLoader() {
        return context.getClassLoader();
    }

    @Override
    public RunMonitor monitor() {
        return core.monitor();
    }

    @Override
    public boolean setOverlayVisible(boolean visible) {
        return core.setOverlayVisible(visible);
    }
}

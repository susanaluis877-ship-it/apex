package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.TraceEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 当前（或最近一次）运行的状态：运行到哪个状态、第几步、最近的轨迹、结束后的结果。
 * 不管运行是宿主代码发起的还是调试桥发起的都会记录，供状态悬浮窗和调试桥的 {@code status} 命令查看。
 *
 * <p>引擎线程写入，其他线程用 {@link #view()} 读取一份不可变的拷贝。变化时同步回调监听器（在写入的线程上），
 * 监听器不要做耗时操作。
 */
public final class RunMonitor {

    /** 运行中保留的最近轨迹条数；结束后完整轨迹在结果里。 */
    static final int RECENT = 200;

    private final Deque<TraceEvent> recent = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Clock clock;
    private long startedAt;
    private String flowId;
    private String runId;
    private boolean running;
    private String stateId;
    private int step;
    private RunResult result;

    public RunMonitor() {
        this(Clock.SYSTEM);
    }

    /** @param clock 计算运行中的已用时间，测试里换成假时钟 */
    public RunMonitor(Clock clock) {
        this.clock = clock;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void onStart(String runId, String flowId) {
        synchronized (this) {
            this.runId = runId;
            this.flowId = flowId;
            startedAt = clock.now();
            running = true;
            stateId = null;
            step = 0;
            result = null;
            recent.clear();
        }
        changed();
    }

    public void onStateEntered(String stateId, int step) {
        synchronized (this) {
            this.stateId = stateId;
            this.step = step;
        }
        changed();
    }

    public void onTrace(TraceEvent event) {
        synchronized (this) {
            recent.addLast(event);
            if (recent.size() > RECENT) {
                recent.removeFirst();
            }
        }
        changed();
    }

    public void onFinish(RunResult result) {
        synchronized (this) {
            running = false;
            this.result = result;
        }
        changed();
    }

    public synchronized View view() {
        // 运行中按时钟算：长动作（例如拨滚轮）期间没有新轨迹，取最近一条轨迹的时间会一直不动
        long elapsed = result != null ? result.getDurationMs() : running ? clock.now() - startedAt : 0;
        return new View(flowId, runId, running, stateId, step, new ArrayList<>(recent), result, elapsed);
    }

    private void changed() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** 某一时刻的状态拷贝。从没运行过时 {@link #getFlowId()} 为 null。 */
    public static final class View {
        private final String flowId;
        private final String runId;
        private final boolean running;
        private final String stateId;
        private final int step;
        private final List<TraceEvent> recent;
        private final RunResult result;
        private final long elapsedMs;

        View(String flowId, String runId, boolean running, String stateId, int step, List<TraceEvent> recent,
             RunResult result, long elapsedMs) {
            this.flowId = flowId;
            this.runId = runId;
            this.running = running;
            this.stateId = stateId;
            this.step = step;
            this.recent = Collections.unmodifiableList(recent);
            this.result = result;
            this.elapsedMs = elapsedMs;
        }

        public String getFlowId() {
            return flowId;
        }

        public String getRunId() {
            return runId;
        }

        public boolean isRunning() {
            return running;
        }

        /** 最近进入的状态，还没进入任何状态时为 null。 */
        public String getStateId() {
            return stateId;
        }

        public int getStep() {
            return step;
        }

        /** 最近的轨迹（最多 {@value RunMonitor#RECENT} 条）。 */
        public List<TraceEvent> getRecent() {
            return recent;
        }

        /** 结束后的结果，运行中为 null。 */
        public RunResult getResult() {
            return result;
        }

        /** 运行了多久：运行中是从开始到取这份拷贝的时间，结束后是结果里的耗时，从没运行过时为 0。 */
        public long getElapsedMs() {
            return elapsedMs;
        }
    }
}

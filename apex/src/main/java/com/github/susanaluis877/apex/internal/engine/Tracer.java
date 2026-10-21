package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.ApexLogger;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.internal.ApexLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一次运行的轨迹记录器：每条事件都完整记录（有上限），同时按日志级别输出，每行带 {@code [runId]} 前缀。
 *
 * <p>超过上限时保留开头 {@link #HEAD} 条和最近的记录，丢弃中间的部分。只在引擎线程上使用。
 */
final class Tracer {

    static final int MAX_EVENTS = 1000;
    static final int HEAD = 100;

    private final String runId;
    private final Clock clock;
    private final List<TraceEvent> head = new ArrayList<>();
    private final Deque<TraceEvent> tail = new ArrayDeque<>();
    private long startedAt;
    private int dropped;
    private Consumer<TraceEvent> sink;

    Tracer(String runId, Clock clock) {
        this.runId = runId;
        this.clock = clock;
    }

    /** 每条事件记录后同步回调，例如把轨迹实时推给 devtools 客户端。 */
    void setSink(Consumer<TraceEvent> sink) {
        this.sink = sink;
    }

    void start(long startedAt) {
        this.startedAt = startedAt;
    }

    long elapsed() {
        return clock.now() - startedAt;
    }

    /** 记录一条轨迹并输出日志。 */
    void event(TraceEvent.Type type, int priority, String stateId, String message) {
        TraceEvent event = new TraceEvent(elapsed(), type, priority, stateId, message);
        if (head.size() < HEAD) {
            head.add(event);
        } else {
            tail.addLast(event);
            if (tail.size() > MAX_EVENTS - HEAD) {
                tail.removeFirst();
                dropped++;
            }
        }
        if (ApexLog.isLoggable(priority)) {
            ApexLog.log(priority, "[" + runId + "] " + event, null);
        }
        if (sink != null) {
            try {
                sink.accept(event);
            } catch (RuntimeException e) {
                ApexLog.w("[" + runId + "] trace sink threw " + e);
            }
        }
    }

    /** 只输出日志、不进轨迹的细节，例如每一轮的快照概况。 */
    void verbose(String message) {
        if (ApexLog.isLoggable(ApexLogger.VERBOSE)) {
            ApexLog.log(ApexLogger.VERBOSE,
                    "[" + runId + "] +" + TraceEvent.formatSeconds(elapsed()) + " " + message, null);
        }
    }

    List<TraceEvent> events() {
        List<TraceEvent> all = new ArrayList<>(head.size() + tail.size());
        all.addAll(head);
        all.addAll(tail);
        return all;
    }

    int dropped() {
        return dropped;
    }
}

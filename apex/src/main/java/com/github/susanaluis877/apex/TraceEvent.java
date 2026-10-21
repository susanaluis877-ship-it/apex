package com.github.susanaluis877.apex;

/**
 * 运行轨迹里的一条记录。一次运行的完整轨迹通过 {@link RunResult#getTrace()} 返回，不受日志级别影响。
 */
public final class TraceEvent {

    public enum Type {
        /** 流程开始，记录起始状态、状态列表和限制。 */
        RUN_START,
        /** 进入一个状态，记录当时的屏幕概况。 */
        STATE_ENTER,
        /** 执行一个动作：目标节点、执行方式、结果和耗时。 */
        ACTION,
        /** 拦截器触发，或匹配但还在冷却中。 */
        INTERCEPTOR,
        /** 在等待：每个候选状态为什么还没匹配。页面有变化或每隔几秒记录一次。 */
        WAIT,
        /** 页面在重试间隔内毫无变化，重跑当前状态的动作。 */
        RETRY,
        /** 从上次软失败的动作处接着执行。 */
        RESUME,
        /**
         * 几个状态在毫无变化的页面上反复轮流进入，常见原因是它们的条件同时匹配同一页，或者动作没有改变页面。
         * 只是提示，不会结束流程（轮询类流程本来就会这样）。
         */
        LOOP,
        /** 无障碍服务断开或恢复。 */
        SERVICE,
        /** 快照超出节点数、深度或耗时上限，这一轮跳过。 */
        SNAPSHOT,
        /** 流程没有成功结束时，最后一屏的节点树。 */
        SCREEN_DUMP,
        /** 流程结束。 */
        RUN_END
    }

    private final long timeMs;
    private final Type type;
    private final int priority;
    private final String stateId;
    private final String message;

    public TraceEvent(long timeMs, Type type, int priority, String stateId, String message) {
        this.timeMs = timeMs;
        this.type = type;
        this.priority = priority;
        this.stateId = stateId;
        this.message = message;
    }

    /** 距离流程开始的毫秒数。 */
    public long getTimeMs() {
        return timeMs;
    }

    public Type getType() {
        return type;
    }

    /** 对应的日志级别，取值同 {@link ApexLogger}。 */
    public int getPriority() {
        return priority;
    }

    /** 事件发生时所在的状态；还没进入任何状态时为 null。 */
    public String getStateId() {
        return stateId;
    }

    public String getMessage() {
        return message;
    }

    /** 例如 {@code +1.234s STATE_ENTER task (step 2) ...}。 */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append('+').append(formatSeconds(timeMs)).append(' ');
        String name = type.name();
        sb.append(name);
        for (int i = name.length(); i < 11; i++) {
            sb.append(' ');
        }
        return sb.append(' ').append(message).toString();
    }

    /** 毫秒格式化成 {@code 1.234s}。 */
    public static String formatSeconds(long ms) {
        long abs = Math.abs(ms);
        String frac = String.valueOf(abs % 1000);
        while (frac.length() < 3) {
            frac = "0" + frac;
        }
        return (ms < 0 ? "-" : "") + abs / 1000 + "." + frac + "s";
    }
}

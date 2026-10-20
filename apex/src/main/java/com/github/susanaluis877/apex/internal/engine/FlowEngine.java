package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.ApexLogger;
import com.github.susanaluis877.apex.FlowListener;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.RunResult.Status;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.TraceEvent.Type;
import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.flow.Interceptor;
import com.github.susanaluis877.apex.flow.State;
import com.github.susanaluis877.apex.internal.ApexLog;
import com.github.susanaluis877.apex.internal.ScreenDumper;
import com.github.susanaluis877.apex.node.ScreenSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程状态机的执行引擎。在调用线程上同步运行一个流程直到结束，一个实例只能运行一次。
 *
 * <p>每一轮：等界面安静 → 抓快照 → 先检查拦截器 → 再检查候选状态 → 都没有进展时按规则续跑或重试 → 检查超时。
 * <ul>
 *   <li>动作只在三种情况下执行：进入状态、从软失败的动作处续跑、页面在重试间隔内毫无变化时重跑。</li>
 *   <li>拦截器处理完后不会重跑当前状态的动作；如果弹窗吞掉了点击，会由“页面无变化”的重试补上。</li>
 *   <li>当前状态和候选状态都不匹配时（过渡页、动画）只等待，不重试。</li>
 * </ul>
 *
 * <p>每一步都记入运行轨迹（{@link RunResult#getTrace()}）并按级别输出日志：进入状态、每个动作的目标和结果、
 * 拦截器、等待时每个候选状态没匹配的原因、重试、续跑、服务断连，失败时还有最后一屏的节点树。
 */
public final class FlowEngine {

    private static final int MAX_PATH = 200;
    /** 页面没有变化时，等待记录最多隔这么久写一次。 */
    private static final long WAIT_HEARTBEAT_MS = 5_000;

    private final String runId;
    private final Flow flow;
    /** 本次运行生效的拦截器：流程自己的在前，全局的在后。 */
    private final List<Interceptor> interceptors;
    private final Set<String> globalIds = new HashSet<>();
    private final ScreenSource source;
    private final Clock clock;
    private final Waiter waiter;
    private final FlowListener listener;
    private final EngineConfig config;
    private final Tracer tracer;
    private final ActionRunner runner;

    private final List<String> path = new ArrayList<>();
    private final Map<String, Integer> interceptorCounts = new LinkedHashMap<>();
    private final Map<String, Long> interceptorLastFired = new HashMap<>();
    private int totalInterceptions;
    private long startedAt;
    private long stateDeadline;
    private State current;
    private ScreenSnapshot lastScreen;
    private int tick;
    private Long lastWaitFingerprint;
    private long lastWaitLoggedAt;

    /**
     * @param runId              本次运行的编号，出现在每一行日志和结果里
     * @param globalInterceptors 运行开始时的全局拦截器快照，按 {@link Flow#effectiveInterceptors} 的规则合并
     * @param listener           只会收到 onStateEntered 和 onInterceptor，在引擎线程上同步调用；结果由 {@link #run()} 返回
     */
    public FlowEngine(String runId, Flow flow, List<Interceptor> globalInterceptors, ScreenSource source,
                      ActionExecutor executor, Clock clock, Waiter waiter, FlowListener listener,
                      EngineConfig config) {
        this.runId = runId;
        this.flow = flow;
        this.interceptors = flow.effectiveInterceptors(globalInterceptors);
        Set<String> own = new HashSet<>();
        for (Interceptor interceptor : flow.getInterceptors()) {
            own.add(interceptor.getId());
        }
        for (Interceptor interceptor : interceptors) {
            if (!own.contains(interceptor.getId())) {
                globalIds.add(interceptor.getId());
            }
        }
        this.source = source;
        this.clock = clock;
        this.waiter = waiter;
        this.listener = listener;
        this.config = config;
        this.tracer = new Tracer(runId, clock);
        this.runner = new ActionRunner(source, executor, waiter, tracer);
    }

    public RunResult run() {
        startedAt = clock.now();
        tracer.start(startedAt);
        tracer.event(Type.RUN_START, ApexLogger.INFO, null, describeFlow());
        Finish finish;
        try {
            finish = loop();
        } catch (RuntimeException e) {
            ApexLog.e("[" + runId + "] engine crashed", e);
            finish = new Finish(Status.ERROR, "engine error: " + e);
        }
        String endState = current != null ? current.getId() : null;
        if (needsScreenDump(finish.status) && lastScreen != null) {
            tracer.event(Type.SCREEN_DUMP, ApexLogger.DEBUG, endState,
                    "last screen:\n" + ScreenDumper.dump(lastScreen).trim());
        }
        long duration = clock.now() - startedAt;
        tracer.event(Type.RUN_END, endPriority(finish.status), endState, finish.status
                + (endState != null ? " at '" + endState + "'" : "") + " in " + TraceEvent.formatSeconds(duration)
                + ", path=" + path + ", interceptors=" + interceptorCounts
                + (finish.message != null ? "; " + finish.message : ""));
        return new RunResult(finish.status, flow.getId(), runId, endState, path, interceptorCounts, duration,
                finish.message, tracer.events(), tracer.dropped());
    }

    private Finish loop() {
        long flowDeadline = startedAt + flow.getTimeoutMs();
        List<State> candidates = Collections.singletonList(flow.getStartState());
        stateDeadline = startedAt + flow.getStartState().getTimeoutMs();
        long stateStartedAt = startedAt;
        int steps = 0;
        int retries = 0;
        int pendingIndex = -1;
        String pendingReason = null;
        Long pendingFingerprint = null;
        long lastActionAt = startedAt;
        Long baseline = null;
        long serviceLostAt = -1;
        boolean firstTick = true;
        waiter.clearEvents();

        while (true) {
            if (waiter.isCancelled()) {
                return new Finish(Status.CANCELLED, "cancelled");
            }
            if (clock.now() >= flowDeadline) {
                return new Finish(Status.TIMEOUT, "flow timed out after " + flow.getTimeoutMs() + "ms; "
                        + describe(lastScreen));
            }
            if (!firstTick) {
                settle();
                if (waiter.isCancelled()) {
                    return new Finish(Status.CANCELLED, "cancelled");
                }
            }
            firstTick = false;
            tick++;

            long captureStart = clock.now();
            ScreenSnapshot screen = source.snapshot();
            long now = clock.now();
            if (screen == null) {
                if (serviceLostAt < 0) {
                    serviceLostAt = now;
                    tracer.event(Type.SERVICE, ApexLogger.WARN, currentId(), "accessibility service unavailable,"
                            + " giving it " + TraceEvent.formatSeconds(config.serviceGraceMs) + " to come back");
                }
                if (now - serviceLostAt >= config.serviceGraceMs) {
                    return new Finish(Status.SERVICE_UNAVAILABLE, "accessibility service unavailable for "
                            + TraceEvent.formatSeconds(now - serviceLostAt));
                }
                continue;
            }
            if (serviceLostAt >= 0) {
                tracer.event(Type.SERVICE, ApexLogger.INFO, currentId(),
                        "service available again after " + TraceEvent.formatSeconds(now - serviceLostAt));
                serviceLostAt = -1;
            }
            lastScreen = screen;
            tracer.verbose("tick " + tick + ": " + summary(screen) + ", captured in " + (now - captureStart) + "ms");
            if (screen.isPartial()) {
                tracer.event(Type.SNAPSHOT, ApexLogger.WARN, currentId(),
                        "partial snapshot skipped (node, depth or time budget exceeded): " + summary(screen));
                if (now >= stateDeadline) {
                    return timeout(retries, candidates);
                }
                continue;
            }

            // 1. 拦截器优先；匹配但还在冷却中时本轮什么都不做，等弹窗消失或冷却结束
            Pick pick = pickInterceptor(screen, now);
            if (pick != null && pick.coolingMs > 0) {
                tracer.event(Type.INTERCEPTOR, ApexLogger.DEBUG, currentId(), pick.interceptor.getId()
                        + " still matches, cooling down " + TraceEvent.formatSeconds(pick.coolingMs)
                        + " more; skipping this tick");
                if (now >= stateDeadline) {
                    return timeout(retries, candidates);
                }
                continue;
            }
            if (pick != null) {
                ActionResult r = fireInterceptor(pick.interceptor, screen);
                if (isFatal(r)) {
                    return fatal(r);
                }
                lastActionAt = clock.now();
                baseline = null;
                continue;
            }

            // 2. 候选状态，按声明顺序第一个匹配的胜出
            State entered = null;
            for (State candidate : candidates) {
                if (candidate.getMatcher().matches(screen)) {
                    entered = candidate;
                    break;
                }
            }
            if (entered != null) {
                if (++steps > flow.getMaxSteps()) {
                    return new Finish(Status.MAX_STEPS, "exceeded maxSteps " + flow.getMaxSteps()
                            + " when entering '" + entered.getId() + "'");
                }
                current = entered;
                if (path.size() < MAX_PATH) {
                    path.add(entered.getId());
                }
                tracer.event(Type.STATE_ENTER, ApexLogger.INFO, entered.getId(), entered.getId()
                        + " (step " + steps + ")" + (entered.isTerminal() ? " [end: " + entered.getEnd() + "]" : "")
                        + " on " + summary(screen));
                notifyStateEntered(entered.getId(), steps);
                long before = Fingerprint.of(screen);
                Outcome o = runActions(entered.getId(), entered.getActions(), 0, screen);
                if (o.fatal()) {
                    return fatal(o.result);
                }
                if (entered.isTerminal()) {
                    return new Finish(entered.getEnd() == State.End.SUCCESS ? Status.SUCCESS : Status.FAILURE, null);
                }
                candidates = resolve(entered.getNext());
                retries = 0;
                pendingIndex = o.failedIndex;
                pendingReason = o.result.getMessage();
                pendingFingerprint = before;
                stateDeadline = clock.now() + entered.getTimeoutMs();
                stateStartedAt = clock.now();
                lastActionAt = clock.now();
                baseline = before;
                lastWaitFingerprint = null;
                continue;
            }

            // 3. 没有进展：续跑软失败的动作，或在页面毫无变化时重试
            boolean currentMatches = current != null && current.getMatcher().matches(screen);
            long fp = Fingerprint.of(screen);
            boolean acted = false;
            if (currentMatches) {
                if (pendingIndex >= 0) {
                    // 页面没变时续跑只会得到同样的失败，所以等页面变化或过了重试间隔再试
                    if (pendingFingerprint == null || fp != pendingFingerprint
                            || now - lastActionAt >= current.getRetryIntervalMs()) {
                        tracer.event(Type.RESUME, ApexLogger.DEBUG, current.getId(), current.getId()
                                + " from action " + (pendingIndex + 1) + "/" + current.getActions().size()
                                + " (last attempt: " + pendingReason + ")");
                        Outcome o = runActions(current.getId(), current.getActions(), pendingIndex, screen);
                        if (o.fatal()) {
                            return fatal(o.result);
                        }
                        pendingIndex = o.failedIndex;
                        pendingReason = o.result.getMessage();
                        pendingFingerprint = fp;
                        lastActionAt = clock.now();
                        baseline = null;
                        acted = true;
                    }
                } else if (baseline == null || fp != baseline) {
                    baseline = fp;
                    lastActionAt = now;
                } else if (now - lastActionAt >= current.getRetryIntervalMs()
                        && retries < current.getMaxRetries()) {
                    retries++;
                    tracer.event(Type.RETRY, ApexLogger.INFO, current.getId(), current.getId() + " " + retries
                            + "/" + current.getMaxRetries() + ": screen unchanged for "
                            + TraceEvent.formatSeconds(now - lastActionAt));
                    Outcome o = runActions(current.getId(), current.getActions(), 0, screen);
                    if (o.fatal()) {
                        return fatal(o.result);
                    }
                    pendingIndex = o.failedIndex;
                    pendingReason = o.result.getMessage();
                    pendingFingerprint = fp;
                    lastActionAt = clock.now();
                    acted = true;
                }
            }
            if (!acted) {
                traceWait(screen, fp, now, candidates, currentMatches, retries, pendingIndex, stateStartedAt);
            }
            if (clock.now() >= stateDeadline) {
                return timeout(retries, candidates);
            }
        }
    }

    /** 等界面安静下来：没有事件时最多等一个轮询间隔；有事件时等到安静 quietMs，最长 settleMaxMs。 */
    private void settle() {
        long start = clock.now();
        if (!waiter.awaitEvent(config.pollIntervalMs)) {
            return;
        }
        while (!waiter.isCancelled()) {
            long remaining = config.settleMaxMs - (clock.now() - start);
            if (remaining <= 0 || !waiter.awaitEvent(Math.min(config.quietMs, remaining))) {
                return;
            }
        }
    }

    /**
     * 记录正在等什么、每个候选状态为什么还没匹配。页面有变化时记一次，页面不变时每 {@link #WAIT_HEARTBEAT_MS} 记一次。
     */
    private void traceWait(ScreenSnapshot screen, long fp, long now, List<State> candidates, boolean currentMatches,
                           int retries, int pendingIndex, long stateStartedAt) {
        if (lastWaitFingerprint != null && fp == lastWaitFingerprint && now - lastWaitLoggedAt < WAIT_HEARTBEAT_MS) {
            return;
        }
        lastWaitFingerprint = fp;
        lastWaitLoggedAt = now;
        String elapsed = " (" + TraceEvent.formatSeconds(now - stateStartedAt) + "/"
                + TraceEvent.formatSeconds(stateDeadline - stateStartedAt);
        StringBuilder sb = new StringBuilder();
        if (current == null) {
            sb.append("start '").append(flow.getStart()).append("'").append(elapsed).append(')');
        } else {
            sb.append("next of '").append(current.getId()).append("' ").append(current.getNext()).append(elapsed)
                    .append(", retries ").append(retries).append('/').append(current.getMaxRetries());
            if (pendingIndex >= 0) {
                sb.append(", action ").append(pendingIndex + 1).append('/').append(current.getActions().size())
                        .append(" pending");
            }
            sb.append(')');
            if (currentMatches) {
                sb.append("; '").append(current.getId()).append("' still on screen");
            } else {
                sb.append("; left '").append(current.getId()).append("' (")
                        .append(current.getMatcher().explainMismatch(screen)).append(')');
            }
        }
        for (State candidate : candidates) {
            sb.append("; ").append(candidate.getId()).append(": ")
                    .append(candidate.getMatcher().explainMismatch(screen));
        }
        tracer.event(Type.WAIT, ApexLogger.DEBUG, currentId(), sb.toString());
    }

    /**
     * 按声明顺序找第一个匹配的拦截器。次数用完的拦截器（以及总次数用完后的所有拦截器）视为不存在；
     * 匹配但还在冷却中时返回带剩余冷却时间的结果，避免在弹窗关闭动画期间点到下面的内容。
     */
    private Pick pickInterceptor(ScreenSnapshot screen, long now) {
        if (totalInterceptions >= flow.getMaxInterceptions()) {
            return null;
        }
        for (Interceptor interceptor : interceptors) {
            Integer count = interceptorCounts.get(interceptor.getId());
            if (count != null && count >= interceptor.getMaxTimes()) {
                continue;
            }
            if (interceptor.getMatcher().matches(screen)) {
                Long last = interceptorLastFired.get(interceptor.getId());
                long cooling = last == null ? 0 : interceptor.getCooldownMs() - (now - last);
                return new Pick(interceptor, Math.max(0, cooling));
            }
        }
        return null;
    }

    /** 执行拦截器的动作。动作软失败只记录；耗时会补加到当前状态的超时上。 */
    private ActionResult fireInterceptor(Interceptor interceptor, ScreenSnapshot screen) {
        long start = clock.now();
        int count = interceptorCounts.getOrDefault(interceptor.getId(), 0) + 1;
        interceptorCounts.put(interceptor.getId(), count);
        totalInterceptions++;
        tracer.event(Type.INTERCEPTOR, ApexLogger.INFO, currentId(), interceptor.getId() + " (" + count + "/"
                + interceptor.getMaxTimes() + (globalIds.contains(interceptor.getId()) ? ", global" : "") + ") on "
                + summary(screen));
        notifyInterceptor(interceptor.getId(), count);
        String owner = "interceptor " + interceptor.getId();
        List<Action> actions = interceptor.getActions();
        ScreenSnapshot s = screen;
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) {
                waiter.sleep(flow.getDefaults().getActionGapMs());
                if (waiter.isCancelled()) {
                    return ActionResult.cancelled();
                }
                s = source.snapshot();
                if (s == null) {
                    break;
                }
            }
            Action action = actions.get(i);
            ActionResult r = runAction(owner, action, i, actions.size(), s);
            if (r.isOk() || action.isOptional() && !isFatalAlways(r)) {
                continue;
            }
            if (isFatal(r)) {
                return r;
            }
            break;
        }
        long end = clock.now();
        interceptorLastFired.put(interceptor.getId(), end);
        stateDeadline += end - start;
        return ActionResult.OK;
    }

    /** 从 {@code from} 开始依次执行动作。相邻动作之间会重新抓快照，并先处理出现的拦截器。 */
    private Outcome runActions(String owner, List<Action> actions, int from, ScreenSnapshot screen) {
        ScreenSnapshot s = screen;
        for (int i = from; i < actions.size(); i++) {
            if (i > from) {
                waiter.sleep(flow.getDefaults().getActionGapMs());
                if (waiter.isCancelled()) {
                    return new Outcome(ActionResult.cancelled(), i);
                }
                s = source.snapshot();
                if (s == null) {
                    return new Outcome(ActionResult.soft("service unavailable"), i);
                }
                Pick pick = pickInterceptor(s, clock.now());
                if (pick != null && pick.coolingMs > 0) {
                    return new Outcome(ActionResult.soft("interceptor " + pick.interceptor.getId()
                            + " cooling down"), i);
                }
                if (pick != null) {
                    ActionResult r = fireInterceptor(pick.interceptor, s);
                    if (isFatal(r)) {
                        return new Outcome(r, i);
                    }
                    waiter.sleep(flow.getDefaults().getActionGapMs());
                    s = source.snapshot();
                    if (s == null) {
                        return new Outcome(ActionResult.soft("service unavailable"), i);
                    }
                }
            }
            Action action = actions.get(i);
            ActionResult r = runAction(owner, action, i, actions.size(), s);
            if (r.isOk()) {
                continue;
            }
            if (isFatalAlways(r)) {
                return new Outcome(r, i);
            }
            if (action.isOptional()) {
                continue;
            }
            return new Outcome(r, i);
        }
        return new Outcome(ActionResult.OK, -1);
    }

    /** 执行单个动作并记入轨迹：谁的第几个动作、做了什么、结果、耗时。 */
    private ActionResult runAction(String owner, Action action, int index, int total, ScreenSnapshot screen) {
        long start = clock.now();
        ActionResult r = runner.run(action, screen, currentId());
        long took = clock.now() - start;
        int priority;
        switch (r.getStatus()) {
            case OK:
            case SOFT_FAIL:
                priority = ApexLogger.DEBUG;
                break;
            case CANCELLED:
                priority = ApexLogger.INFO;
                break;
            default:
                priority = ApexLogger.ERROR;
                break;
        }
        boolean skipped = !r.isOk() && action.isOptional() && !isFatalAlways(r);
        tracer.event(Type.ACTION, priority, currentId(), owner + " " + (index + 1) + "/" + total + " " + action
                + " -> " + r.getStatus() + " in " + took + "ms" + (skipped ? " (optional, skipped)" : "")
                + (r.getMessage() != null ? ": " + r.getMessage() : ""));
        return r;
    }

    private List<State> resolve(List<String> ids) {
        List<State> states = new ArrayList<>(ids.size());
        for (String id : ids) {
            states.add(flow.getState(id));
        }
        return states;
    }

    private String currentId() {
        return current != null ? current.getId() : null;
    }

    /** ERROR 和 CANCELLED 无论动作是否 optional 都会结束流程。 */
    private static boolean isFatalAlways(ActionResult r) {
        return r.getStatus() == ActionResult.Status.ERROR || r.getStatus() == ActionResult.Status.CANCELLED;
    }

    private static boolean isFatal(ActionResult r) {
        return isFatalAlways(r) || r.getStatus() == ActionResult.Status.HARD_FAIL;
    }

    private static Finish fatal(ActionResult r) {
        switch (r.getStatus()) {
            case CANCELLED:
                return new Finish(Status.CANCELLED, "cancelled");
            case ERROR:
                return new Finish(Status.ERROR, r.getMessage());
            default:
                return new Finish(Status.ACTION_FAILED, r.getMessage());
        }
    }

    private Finish timeout(int retries, List<State> candidates) {
        StringBuilder sb = new StringBuilder();
        if (current == null) {
            sb.append("start state '").append(flow.getStart()).append("' did not appear within ")
                    .append(flow.getStartState().getTimeoutMs()).append("ms");
        } else {
            sb.append("no next state of '").append(current.getId()).append("' ").append(current.getNext())
                    .append(" appeared within ").append(current.getTimeoutMs()).append("ms (retries=")
                    .append(retries).append(')');
        }
        if (lastScreen != null) {
            for (State candidate : candidates) {
                String why = candidate.getMatcher().explainMismatch(lastScreen);
                if (why != null) {
                    sb.append("; ").append(candidate.getId()).append(": ").append(why);
                }
            }
        }
        return new Finish(Status.TIMEOUT, sb.append("; ").append(describe(lastScreen)).toString());
    }

    private String describeFlow() {
        List<String> names = new ArrayList<>();
        for (Interceptor interceptor : interceptors) {
            names.add(globalIds.contains(interceptor.getId()) ? interceptor.getId() + "(global)" : interceptor.getId());
        }
        List<String> states = new ArrayList<>();
        for (State state : flow.getStates()) {
            states.add(state.getId());
        }
        return "flow " + flow.getId() + ": start '" + flow.getStart() + "', states " + states
                + ", interceptors " + names + ", timeout " + TraceEvent.formatSeconds(flow.getTimeoutMs())
                + ", maxSteps " + flow.getMaxSteps();
    }

    /** 一行屏幕概况：快照序号、包名、Activity 简名、窗口数、节点数。 */
    private static String summary(ScreenSnapshot screen) {
        String activity = screen.getActivity();
        if (activity != null && activity.lastIndexOf('.') >= 0) {
            activity = activity.substring(activity.lastIndexOf('.') + 1);
        }
        return "screen seq=" + screen.getSeq() + " pkg=" + screen.getPackageName() + " activity=" + activity
                + " windows=" + screen.getWindows().size() + " nodes=" + screen.nodes().size();
    }

    private static String describe(ScreenSnapshot screen) {
        if (screen == null) {
            return "no screen captured";
        }
        return "screen: pkg=" + screen.getPackageName() + ", activity=" + screen.getActivity()
                + ", texts=" + screen.topTexts(8);
    }

    private static boolean needsScreenDump(Status status) {
        switch (status) {
            case TIMEOUT:
            case MAX_STEPS:
            case ACTION_FAILED:
            case SERVICE_UNAVAILABLE:
            case ERROR:
                return true;
            default:
                return false;
        }
    }

    private static int endPriority(Status status) {
        switch (status) {
            case SUCCESS:
            case FAILURE:
            case CANCELLED:
                return ApexLogger.INFO;
            case ERROR:
                return ApexLogger.ERROR;
            default:
                return ApexLogger.WARN;
        }
    }

    private void notifyStateEntered(String id, int step) {
        if (listener == null) {
            return;
        }
        try {
            listener.onStateEntered(id, step);
        } catch (RuntimeException e) {
            ApexLog.e("[" + runId + "] listener.onStateEntered threw", e);
        }
    }

    private void notifyInterceptor(String id, int count) {
        if (listener == null) {
            return;
        }
        try {
            listener.onInterceptor(id, count);
        } catch (RuntimeException e) {
            ApexLog.e("[" + runId + "] listener.onInterceptor threw", e);
        }
    }

    private static final class Finish {
        final Status status;
        final String message;

        Finish(Status status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    private static final class Pick {
        final Interceptor interceptor;
        /** 大于 0 表示匹配但还在冷却中。 */
        final long coolingMs;

        Pick(Interceptor interceptor, long coolingMs) {
            this.interceptor = interceptor;
            this.coolingMs = coolingMs;
        }
    }

    private static final class Outcome {
        final ActionResult result;
        /** 软失败的动作下标，全部成功时为 -1。 */
        final int failedIndex;

        Outcome(ActionResult result, int index) {
            this.result = result;
            this.failedIndex = result.isOk() ? -1 : index;
        }

        boolean fatal() {
            return isFatal(result);
        }
    }
}

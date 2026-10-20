package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.FlowListener;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.RunResult.Status;
import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.flow.Interceptor;
import com.github.susanaluis877.apex.flow.State;
import com.github.susanaluis877.apex.internal.ApexLog;
import com.github.susanaluis877.apex.node.ScreenSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程状态机的执行引擎。在调用线程上同步运行一个流程直到结束，一个实例只能运行一次。
 *
 * <p>每一轮：等界面安静 → 抓快照 → 先检查拦截器 → 再检查候选状态 → 都没有进展时按规则续跑或重试 → 检查超时。
 * <ul>
 *   <li>动作只在三种情况下执行：进入状态、从软失败的动作处续跑、页面在重试间隔内毫无变化时重跑。</li>
 *   <li>拦截器处理完后不会重跑当前状态的动作；如果弹窗吞掉了点击，会由“页面无变化”的重试补上。</li>
 *   <li>当前状态和候选状态都不匹配时（过渡页、动画）只等待，不重试。</li>
 * </ul>
 */
public final class FlowEngine {

    private static final int MAX_PATH = 200;

    private final Flow flow;
    private final ScreenSource source;
    private final ActionExecutor executor;
    private final Clock clock;
    private final Waiter waiter;
    private final FlowListener listener;
    private final EngineConfig config;
    private final ActionRunner runner;

    private final List<String> path = new ArrayList<>();
    private final Map<String, Integer> interceptorCounts = new LinkedHashMap<>();
    private final Map<String, Long> interceptorLastFired = new HashMap<>();
    private int totalInterceptions;
    private long startedAt;
    private long stateDeadline;
    private State current;
    private ScreenSnapshot lastScreen;

    /**
     * @param listener 只会收到 onStateEntered 和 onInterceptor，在引擎线程上同步调用；结果由 {@link #run()} 返回
     */
    public FlowEngine(Flow flow, ScreenSource source, ActionExecutor executor, Clock clock, Waiter waiter,
                      FlowListener listener, EngineConfig config) {
        this.flow = flow;
        this.source = source;
        this.executor = executor;
        this.clock = clock;
        this.waiter = waiter;
        this.listener = listener;
        this.config = config;
        this.runner = new ActionRunner(source, executor, waiter);
    }

    public RunResult run() {
        startedAt = clock.now();
        RunResult result;
        try {
            result = loop();
        } catch (RuntimeException e) {
            ApexLog.e("flow " + flow.getId() + " crashed", e);
            result = result(Status.ERROR, "engine error: " + e);
        }
        ApexLog.i("flow " + flow.getId() + " finished: " + result);
        return result;
    }

    private RunResult loop() {
        long flowDeadline = startedAt + flow.getTimeoutMs();
        List<State> candidates = Collections.singletonList(flow.getStartState());
        stateDeadline = startedAt + flow.getStartState().getTimeoutMs();
        int steps = 0;
        int retries = 0;
        int pendingIndex = -1;
        long lastActionAt = startedAt;
        Long baseline = null;
        long serviceLostAt = -1;
        boolean firstTick = true;
        waiter.clearEvents();
        ApexLog.i("flow " + flow.getId() + " started, waiting for '" + flow.getStart() + "'");

        while (true) {
            if (waiter.isCancelled()) {
                return result(Status.CANCELLED, "cancelled");
            }
            if (clock.now() >= flowDeadline) {
                return result(Status.TIMEOUT, "flow timed out after " + flow.getTimeoutMs() + "ms; "
                        + describe(lastScreen));
            }
            if (!firstTick) {
                settle();
                if (waiter.isCancelled()) {
                    return result(Status.CANCELLED, "cancelled");
                }
            }
            firstTick = false;

            ScreenSnapshot screen = source.snapshot();
            long now = clock.now();
            if (screen == null) {
                if (serviceLostAt < 0) {
                    serviceLostAt = now;
                    ApexLog.d("tick: service unavailable");
                }
                if (now - serviceLostAt >= config.serviceGraceMs) {
                    return result(Status.SERVICE_UNAVAILABLE, "accessibility service unavailable");
                }
                continue;
            }
            serviceLostAt = -1;
            lastScreen = screen;
            if (screen.isPartial()) {
                ApexLog.d("tick: partial snapshot skipped " + screen);
                if (now >= stateDeadline) {
                    return timeout(retries);
                }
                continue;
            }

            // 1. 拦截器优先；匹配但还在冷却中时本轮什么都不做，等弹窗消失或冷却结束
            Pick pick = pickInterceptor(screen, now);
            if (pick == Pick.COOLING) {
                if (now >= stateDeadline) {
                    return timeout(retries);
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
                    return result(Status.MAX_STEPS, "exceeded maxSteps " + flow.getMaxSteps()
                            + " when entering '" + entered.getId() + "'");
                }
                current = entered;
                if (path.size() < MAX_PATH) {
                    path.add(entered.getId());
                }
                ApexLog.i("enter '" + entered.getId() + "' (step " + steps + ") " + screen);
                notifyStateEntered(entered.getId(), steps);
                long before = Fingerprint.of(screen);
                Outcome o = runActions(entered.getActions(), 0, screen);
                if (o.fatal()) {
                    return fatal(o.result);
                }
                if (entered.isTerminal()) {
                    Status status = entered.getEnd() == State.End.SUCCESS ? Status.SUCCESS : Status.FAILURE;
                    return result(status, null);
                }
                candidates = resolve(entered.getNext());
                retries = 0;
                pendingIndex = o.failedIndex;
                stateDeadline = clock.now() + entered.getTimeoutMs();
                lastActionAt = clock.now();
                baseline = before;
                continue;
            }

            // 3. 没有进展：续跑软失败的动作，或在页面毫无变化时重试
            if (current != null && current.getMatcher().matches(screen)) {
                if (pendingIndex >= 0) {
                    ApexLog.d("resume '" + current.getId() + "' from action " + pendingIndex);
                    Outcome o = runActions(current.getActions(), pendingIndex, screen);
                    if (o.fatal()) {
                        return fatal(o.result);
                    }
                    pendingIndex = o.failedIndex;
                    lastActionAt = clock.now();
                    baseline = null;
                } else {
                    long fp = Fingerprint.of(screen);
                    if (baseline == null || fp != baseline) {
                        baseline = fp;
                        lastActionAt = now;
                    } else if (now - lastActionAt >= current.getRetryIntervalMs()
                            && retries < current.getMaxRetries()) {
                        retries++;
                        ApexLog.i("retry '" + current.getId() + "' (" + retries + "/" + current.getMaxRetries()
                                + "), screen unchanged for " + (now - lastActionAt) + "ms");
                        Outcome o = runActions(current.getActions(), 0, screen);
                        if (o.fatal()) {
                            return fatal(o.result);
                        }
                        pendingIndex = o.failedIndex;
                        lastActionAt = clock.now();
                    }
                }
            }
            if (clock.now() >= stateDeadline) {
                return timeout(retries);
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
     * 按声明顺序找第一个匹配的拦截器。次数用完的拦截器（以及总次数用完后的所有拦截器）视为不存在；
     * 匹配但还在冷却中时返回 {@link Pick#COOLING}，避免在弹窗关闭动画期间点到下面的内容。
     */
    private Pick pickInterceptor(ScreenSnapshot screen, long now) {
        if (totalInterceptions >= flow.getMaxInterceptions()) {
            return null;
        }
        for (Interceptor interceptor : flow.getInterceptors()) {
            Integer count = interceptorCounts.get(interceptor.getId());
            if (count != null && count >= interceptor.getMaxTimes()) {
                continue;
            }
            if (interceptor.getMatcher().matches(screen)) {
                Long last = interceptorLastFired.get(interceptor.getId());
                return last != null && now - last < interceptor.getCooldownMs()
                        ? Pick.COOLING
                        : new Pick(interceptor);
            }
        }
        return null;
    }

    /** 执行拦截器的动作。动作软失败只记日志；耗时会补加到当前状态的超时上。 */
    private ActionResult fireInterceptor(Interceptor interceptor, ScreenSnapshot screen) {
        long start = clock.now();
        int count = interceptorCounts.getOrDefault(interceptor.getId(), 0) + 1;
        interceptorCounts.put(interceptor.getId(), count);
        totalInterceptions++;
        ApexLog.i("interceptor '" + interceptor.getId() + "' (" + count + ") " + screen);
        notifyInterceptor(interceptor.getId(), count);
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
            ActionResult r = runner.run(action, s);
            ApexLog.d("  " + action + " -> " + r);
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
    private Outcome runActions(List<Action> actions, int from, ScreenSnapshot screen) {
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
                if (pick == Pick.COOLING) {
                    return new Outcome(ActionResult.soft("interceptor cooling down"), i);
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
            ActionResult r = runner.run(action, s);
            ApexLog.d("  " + action + " -> " + r);
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

    private List<State> resolve(List<String> ids) {
        List<State> states = new ArrayList<>(ids.size());
        for (String id : ids) {
            states.add(flow.getState(id));
        }
        return states;
    }

    /** ERROR 和 CANCELLED 无论动作是否 optional 都会结束流程。 */
    private static boolean isFatalAlways(ActionResult r) {
        return r.getStatus() == ActionResult.Status.ERROR || r.getStatus() == ActionResult.Status.CANCELLED;
    }

    private static boolean isFatal(ActionResult r) {
        return isFatalAlways(r) || r.getStatus() == ActionResult.Status.HARD_FAIL;
    }

    private RunResult fatal(ActionResult r) {
        switch (r.getStatus()) {
            case CANCELLED:
                return result(Status.CANCELLED, "cancelled");
            case ERROR:
                return result(Status.ERROR, r.getMessage());
            default:
                return result(Status.ACTION_FAILED, r.getMessage());
        }
    }

    private RunResult timeout(int retries) {
        String message;
        if (current == null) {
            message = "start state '" + flow.getStart() + "' did not appear within "
                    + flow.getStartState().getTimeoutMs() + "ms";
        } else {
            message = "no next state of '" + current.getId() + "' " + current.getNext() + " appeared within "
                    + current.getTimeoutMs() + "ms (retries=" + retries + ")";
        }
        return result(Status.TIMEOUT, message + "; " + describe(lastScreen));
    }

    private static String describe(ScreenSnapshot screen) {
        if (screen == null) {
            return "no screen captured";
        }
        return "screen: pkg=" + screen.getPackageName() + ", activity=" + screen.getActivity()
                + ", texts=" + screen.topTexts(8);
    }

    private RunResult result(Status status, String message) {
        return new RunResult(status, flow.getId(), current != null ? current.getId() : null, path,
                interceptorCounts, clock.now() - startedAt, message);
    }

    private void notifyStateEntered(String id, int step) {
        if (listener == null) {
            return;
        }
        try {
            listener.onStateEntered(id, step);
        } catch (RuntimeException e) {
            ApexLog.e("listener.onStateEntered threw", e);
        }
    }

    private void notifyInterceptor(String id, int count) {
        if (listener == null) {
            return;
        }
        try {
            listener.onInterceptor(id, count);
        } catch (RuntimeException e) {
            ApexLog.e("listener.onInterceptor threw", e);
        }
    }

    private static final class Pick {
        static final Pick COOLING = new Pick(null);

        final Interceptor interceptor;

        Pick(Interceptor interceptor) {
            this.interceptor = interceptor;
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

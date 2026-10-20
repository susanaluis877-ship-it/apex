package com.github.susanaluis877.apex.flow;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.flow.FlowValidationException.Problem;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.match.ScreenMatcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 一个完整的自动化流程：状态图 + 全局拦截器 + 运行限制。不可变，可以重复运行。
 *
 * <p>用 {@link Builder} 在代码里定义，或用 {@link FlowParser} 从 JSON 解析，两者产出相同的对象。
 * 引用自定义动作或条件时，必须先通过 {@code Apex.registerAction}/{@code Apex.registerMatcher} 注册，再构建流程。
 */
public final class Flow {

    public static final long DEFAULT_TIMEOUT_MS = 5 * 60_000;
    public static final int DEFAULT_MAX_STEPS = 100;
    public static final int DEFAULT_MAX_INTERCEPTIONS = 30;

    private final String id;
    private final String start;
    private final Map<String, State> states;
    private final List<Interceptor> interceptors;
    private final long timeoutMs;
    private final int maxSteps;
    private final int maxInterceptions;
    private final FlowDefaults defaults;

    private Flow(Builder b, Map<String, State> states, List<Interceptor> interceptors, FlowDefaults defaults) {
        this.id = b.id;
        this.start = b.start;
        this.states = Collections.unmodifiableMap(states);
        this.interceptors = Collections.unmodifiableList(interceptors);
        this.timeoutMs = b.timeoutMs;
        this.maxSteps = b.maxSteps;
        this.maxInterceptions = b.maxInterceptions;
        this.defaults = defaults;
    }

    public String getId() {
        return id;
    }

    public String getStart() {
        return start;
    }

    public State getStartState() {
        return states.get(start);
    }

    public State getState(String id) {
        return states.get(id);
    }

    /** 所有状态，按声明顺序排列。 */
    public List<State> getStates() {
        return new ArrayList<>(states.values());
    }

    public List<Interceptor> getInterceptors() {
        return interceptors;
    }

    /** 整个流程的最长运行时间。 */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /** 最多进入状态的次数，防止状态之间无限循环。 */
    public int getMaxSteps() {
        return maxSteps;
    }

    /** 一次运行中所有拦截器合计最多触发几次。 */
    public int getMaxInterceptions() {
        return maxInterceptions;
    }

    public FlowDefaults getDefaults() {
        return defaults;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Flow)) {
            return false;
        }
        Flow f = (Flow) o;
        return id.equals(f.id) && start.equals(f.start) && getStates().equals(f.getStates())
                && interceptors.equals(f.interceptors) && timeoutMs == f.timeoutMs && maxSteps == f.maxSteps
                && maxInterceptions == f.maxInterceptions && defaults.equals(f.defaults);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, start, getStates(), interceptors, timeoutMs, maxSteps, maxInterceptions, defaults);
    }

    @Override
    public String toString() {
        return "Flow{" + id + ", start=" + start + ", states=" + states.keySet()
                + ", interceptors=" + interceptors.size() + "}";
    }

    public static final class Builder {

        private final String id;
        private String start;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;
        private int maxSteps = DEFAULT_MAX_STEPS;
        private int maxInterceptions = DEFAULT_MAX_INTERCEPTIONS;
        private final FlowDefaults.Builder defaults = new FlowDefaults.Builder();
        private final List<String> stateIds = new ArrayList<>();
        private final List<State.Builder> stateBuilders = new ArrayList<>();
        private final List<String> interceptorIds = new ArrayList<>();
        private final List<Interceptor.Builder> interceptorBuilders = new ArrayList<>();

        public Builder(String id) {
            this.id = id;
        }

        public Builder start(String stateId) {
            this.start = stateId;
            return this;
        }

        public Builder timeoutMs(long value) {
            timeoutMs = value;
            return this;
        }

        public Builder maxSteps(int value) {
            maxSteps = value;
            return this;
        }

        public Builder maxInterceptions(int value) {
            maxInterceptions = value;
            return this;
        }

        public Builder defaults(Consumer<FlowDefaults.Builder> config) {
            config.accept(defaults);
            return this;
        }

        public Builder state(String id, Consumer<State.Builder> config) {
            State.Builder b = new State.Builder();
            config.accept(b);
            stateIds.add(id);
            stateBuilders.add(b);
            return this;
        }

        public Builder interceptor(String id, Consumer<Interceptor.Builder> config) {
            Interceptor.Builder b = new Interceptor.Builder();
            config.accept(b);
            interceptorIds.add(id);
            interceptorBuilders.add(b);
            return this;
        }

        /**
         * @throws FlowValidationException 定义不合法时，包含所有发现的问题
         */
        public Flow build() {
            FlowDefaults d = defaults.build();
            List<Problem> problems = new ArrayList<>();
            validateFlow(d, problems);
            Map<String, State.Builder> byId = validateStates(problems);
            validateInterceptors(problems);
            validateGraph(byId, problems);
            if (!problems.isEmpty()) {
                throw new FlowValidationException(problems);
            }
            Map<String, State> states = new LinkedHashMap<>();
            for (int i = 0; i < stateIds.size(); i++) {
                states.put(stateIds.get(i), new State(stateIds.get(i), stateBuilders.get(i), d));
            }
            List<Interceptor> interceptors = new ArrayList<>();
            for (int i = 0; i < interceptorIds.size(); i++) {
                interceptors.add(new Interceptor(interceptorIds.get(i), interceptorBuilders.get(i), d));
            }
            return new Flow(this, states, interceptors, d);
        }

        private void validateFlow(FlowDefaults d, List<Problem> problems) {
            if (id == null || id.isEmpty()) {
                problems.add(flowProblem("id", "flow id is required"));
            }
            if (timeoutMs <= 0) {
                problems.add(flowProblem("timeoutMs", "must be > 0"));
            }
            if (maxSteps <= 0) {
                problems.add(flowProblem("maxSteps", "must be > 0"));
            }
            if (maxInterceptions < 0) {
                problems.add(flowProblem("maxInterceptions", "must be >= 0"));
            }
            if (d.getTimeoutMs() <= 0) {
                problems.add(flowProblem("defaults.timeoutMs", "must be > 0"));
            }
            if (d.getRetryIntervalMs() <= 0) {
                problems.add(flowProblem("defaults.retryIntervalMs", "must be > 0"));
            }
            if (d.getMaxRetries() < 0) {
                problems.add(flowProblem("defaults.maxRetries", "must be >= 0"));
            }
            if (d.getActionGapMs() < 0) {
                problems.add(flowProblem("defaults.actionGapMs", "must be >= 0"));
            }
            if (d.getInterceptorMaxTimes() <= 0) {
                problems.add(flowProblem("defaults.interceptorMaxTimes", "must be > 0"));
            }
            if (d.getInterceptorCooldownMs() < 0) {
                problems.add(flowProblem("defaults.interceptorCooldownMs", "must be >= 0"));
            }
        }

        private Map<String, State.Builder> validateStates(List<Problem> problems) {
            Map<String, State.Builder> byId = new LinkedHashMap<>();
            if (stateIds.isEmpty()) {
                problems.add(flowProblem("states", "at least one state is required"));
            }
            for (int i = 0; i < stateIds.size(); i++) {
                String sid = stateIds.get(i);
                if (sid == null || sid.isEmpty()) {
                    problems.add(stateProblem(i, sid, "id", -1, "state id is required"));
                } else if (byId.containsKey(sid)) {
                    problems.add(stateProblem(i, sid, "id", -1, "duplicate state id"));
                } else {
                    byId.put(sid, stateBuilders.get(i));
                }
            }
            boolean hasTerminal = false;
            for (int i = 0; i < stateIds.size(); i++) {
                String sid = stateIds.get(i);
                State.Builder b = stateBuilders.get(i);
                boolean terminal = b.endValue() != State.End.NONE;
                hasTerminal |= terminal;
                if (b.matcher() == null) {
                    problems.add(stateProblem(i, sid, "match", -1, "match is required"));
                } else {
                    checkCustomMatchers(b.matcher(), State.class, i, sid, problems);
                }
                checkCustomActions(b.actionList(), State.class, i, sid, problems);
                List<String> next = b.nextList();
                if (terminal && !next.isEmpty()) {
                    problems.add(stateProblem(i, sid, "next", -1, "terminal state cannot have next"));
                }
                if (!terminal && next.isEmpty()) {
                    problems.add(stateProblem(i, sid, "next", -1,
                            "non-terminal state needs at least one next state (or set end)"));
                }
                Set<String> seen = new HashSet<>();
                for (int j = 0; j < next.size(); j++) {
                    String target = next.get(j);
                    if (target == null || !byId.containsKey(target)) {
                        problems.add(stateProblem(i, sid, "next", j, "unknown state '" + target + "'"));
                    } else if (target.equals(sid)) {
                        problems.add(stateProblem(i, sid, "next", j,
                                "state cannot list itself in next (retries already cover staying on a page)"));
                    } else if (!seen.add(target)) {
                        problems.add(stateProblem(i, sid, "next", j, "duplicate next state '" + target + "'"));
                    }
                }
                if (b.timeoutValue() != null && b.timeoutValue() <= 0) {
                    problems.add(stateProblem(i, sid, "timeoutMs", -1, "must be > 0"));
                }
                if (b.retryIntervalValue() != null && b.retryIntervalValue() <= 0) {
                    problems.add(stateProblem(i, sid, "retryIntervalMs", -1, "must be > 0"));
                }
                if (b.maxRetriesValue() != null && b.maxRetriesValue() < 0) {
                    problems.add(stateProblem(i, sid, "maxRetries", -1, "must be >= 0"));
                }
            }
            if (!stateIds.isEmpty() && !hasTerminal) {
                problems.add(flowProblem("states", "flow needs at least one terminal state (end)"));
            }
            if (start == null || start.isEmpty()) {
                problems.add(flowProblem("start", "start state is required"));
            } else if (!byId.containsKey(start)) {
                problems.add(flowProblem("start", "unknown state '" + start + "'"));
            }
            return byId;
        }

        private void validateInterceptors(List<Problem> problems) {
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < interceptorIds.size(); i++) {
                String iid = interceptorIds.get(i);
                Interceptor.Builder b = interceptorBuilders.get(i);
                if (iid == null || iid.isEmpty()) {
                    problems.add(interceptorProblem(i, iid, "id", -1, "interceptor id is required"));
                } else if (!ids.add(iid)) {
                    problems.add(interceptorProblem(i, iid, "id", -1, "duplicate interceptor id"));
                }
                if (b.matcher() == null) {
                    problems.add(interceptorProblem(i, iid, "match", -1, "match is required"));
                } else {
                    checkCustomMatchers(b.matcher(), Interceptor.class, i, iid, problems);
                }
                if (b.actionList().isEmpty()) {
                    problems.add(interceptorProblem(i, iid, "actions", -1, "interceptor needs at least one action"));
                }
                checkCustomActions(b.actionList(), Interceptor.class, i, iid, problems);
                if (b.maxTimesValue() != null && b.maxTimesValue() <= 0) {
                    problems.add(interceptorProblem(i, iid, "maxTimes", -1, "must be > 0"));
                }
                if (b.cooldownValue() != null && b.cooldownValue() < 0) {
                    problems.add(interceptorProblem(i, iid, "cooldownMs", -1, "must be >= 0"));
                }
            }
        }

        private void validateGraph(Map<String, State.Builder> byId, List<Problem> problems) {
            if (start == null || !byId.containsKey(start)) {
                return;
            }
            Set<String> reached = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            reached.add(start);
            while (!queue.isEmpty()) {
                for (String target : byId.get(queue.poll()).nextList()) {
                    if (byId.containsKey(target) && reached.add(target)) {
                        queue.add(target);
                    }
                }
            }
            for (int i = 0; i < stateIds.size(); i++) {
                String sid = stateIds.get(i);
                if (sid != null && byId.get(sid) == stateBuilders.get(i) && !reached.contains(sid)) {
                    problems.add(stateProblem(i, sid, null, -1, "unreachable from start state '" + start + "'"));
                }
            }
        }

        private void checkCustomMatchers(ScreenMatcher matcher, Class<?> owner, int ordinal, String ownerId,
                                         List<Problem> problems) {
            Set<String> names = new TreeSet<>();
            matcher.collectCustomNames(names);
            for (String name : names) {
                if (Registry.get().matcher(name) == null) {
                    problems.add(problem(owner, ordinal, ownerId, "match", -1,
                            "custom matcher '" + name + "' is not registered"));
                }
            }
        }

        private void checkCustomActions(List<Action> actions, Class<?> owner, int ordinal, String ownerId,
                                        List<Problem> problems) {
            for (int k = 0; k < actions.size(); k++) {
                Action a = actions.get(k);
                if (a instanceof Action.Custom && Registry.get().action(((Action.Custom) a).getName()) == null) {
                    problems.add(problem(owner, ordinal, ownerId, "actions", k,
                            "custom action '" + ((Action.Custom) a).getName() + "' is not registered"));
                }
            }
        }

        private static Problem problem(Class<?> owner, int ordinal, String id, String field, int index, String msg) {
            return owner == State.class
                    ? stateProblem(ordinal, id, field, index, msg)
                    : interceptorProblem(ordinal, id, field, index, msg);
        }

        private static Problem flowProblem(String field, String msg) {
            return new Problem(Problem.FLOW, -1, null, field, -1, msg);
        }

        private static Problem stateProblem(int ordinal, String id, String field, int index, String msg) {
            return new Problem(Problem.STATE, ordinal, id, field, index, msg);
        }

        private static Problem interceptorProblem(int ordinal, String id, String field, int index, String msg) {
            return new Problem(Problem.INTERCEPTOR, ordinal, id, field, index, msg);
        }
    }
}

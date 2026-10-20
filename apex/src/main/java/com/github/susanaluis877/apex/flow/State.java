package com.github.susanaluis877.apex.flow;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.match.ScreenMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 状态图里的一个状态：页面匹配条件 + 进入后执行的动作 + 期望的下一状态。
 *
 * <p>{@code next} 是有序的候选列表，引擎按顺序检查每个候选状态的匹配条件，第一个匹配的就是下一状态。
 * 终止状态（{@link End#SUCCESS}/{@link End#FAILURE}）执行完动作后结束流程，不能有 {@code next}。
 */
public final class State {

    /** 状态是否为终止状态，以及流程的结果。 */
    public enum End {
        NONE, SUCCESS, FAILURE
    }

    private final String id;
    private final ScreenMatcher matcher;
    private final List<Action> actions;
    private final List<String> next;
    private final End end;
    private final long timeoutMs;
    private final long retryIntervalMs;
    private final int maxRetries;

    State(String id, Builder b, FlowDefaults defaults) {
        this.id = id;
        this.matcher = b.matcher;
        this.actions = Collections.unmodifiableList(new ArrayList<>(b.actions));
        this.next = Collections.unmodifiableList(new ArrayList<>(b.next));
        this.end = b.end;
        this.timeoutMs = b.timeoutMs != null ? b.timeoutMs : defaults.getTimeoutMs();
        this.retryIntervalMs = b.retryIntervalMs != null ? b.retryIntervalMs : defaults.getRetryIntervalMs();
        this.maxRetries = b.maxRetries != null ? b.maxRetries : defaults.getMaxRetries();
    }

    public String getId() {
        return id;
    }

    public ScreenMatcher getMatcher() {
        return matcher;
    }

    public List<Action> getActions() {
        return actions;
    }

    public List<String> getNext() {
        return next;
    }

    public End getEnd() {
        return end;
    }

    public boolean isTerminal() {
        return end != End.NONE;
    }

    /** 进入状态后等待下一状态的最长时间；起始状态则是等待它出现的最长时间。 */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    /** 页面没有变化时最多重跑几次动作。动作不能重复执行（例如下单）时设为 0。 */
    public int getMaxRetries() {
        return maxRetries;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof State)) {
            return false;
        }
        State s = (State) o;
        return id.equals(s.id) && Objects.equals(matcher, s.matcher) && actions.equals(s.actions)
                && next.equals(s.next) && end == s.end && timeoutMs == s.timeoutMs
                && retryIntervalMs == s.retryIntervalMs && maxRetries == s.maxRetries;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, matcher, actions, next, end, timeoutMs, retryIntervalMs, maxRetries);
    }

    @Override
    public String toString() {
        return "State{" + id + ", match=" + matcher + ", actions=" + actions
                + (isTerminal() ? ", end=" + end : ", next=" + next) + "}";
    }

    public static final class Builder {

        private ScreenMatcher matcher;
        private final List<Action> actions = new ArrayList<>();
        private final List<String> next = new ArrayList<>();
        private End end = End.NONE;
        private Long timeoutMs;
        private Long retryIntervalMs;
        private Integer maxRetries;

        Builder() {
        }

        public Builder match(ScreenMatcher matcher) {
            this.matcher = matcher;
            return this;
        }

        public Builder action(Action... actions) {
            for (Action action : actions) {
                this.actions.add(Objects.requireNonNull(action, "action"));
            }
            return this;
        }

        public Builder actions(List<Action> actions) {
            return action(actions.toArray(new Action[0]));
        }

        public Builder next(String... stateIds) {
            next.addAll(Arrays.asList(stateIds));
            return this;
        }

        public Builder endSuccess() {
            end = End.SUCCESS;
            return this;
        }

        public Builder endFailure() {
            end = End.FAILURE;
            return this;
        }

        public Builder end(End end) {
            this.end = Objects.requireNonNull(end, "end");
            return this;
        }

        public Builder timeoutMs(long value) {
            timeoutMs = value;
            return this;
        }

        public Builder retryIntervalMs(long value) {
            retryIntervalMs = value;
            return this;
        }

        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        ScreenMatcher matcher() {
            return matcher;
        }

        List<Action> actionList() {
            return actions;
        }

        List<String> nextList() {
            return next;
        }

        End endValue() {
            return end;
        }

        Long timeoutValue() {
            return timeoutMs;
        }

        Long retryIntervalValue() {
            return retryIntervalMs;
        }

        Integer maxRetriesValue() {
            return maxRetries;
        }
    }
}

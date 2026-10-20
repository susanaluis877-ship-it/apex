package com.github.susanaluis877.apex.flow;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.match.ScreenMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 全局拦截器：任何时候屏幕匹配它的条件，就先执行它的动作（例如关闭广告弹窗），然后回到被打断的状态继续。
 *
 * <p>每一轮引擎先检查拦截器，再检查候选状态。如果某个弹窗本身就是流程的一个状态，
 * 在拦截器条件里用 {@code not(...)} 把它排除掉。
 */
public final class Interceptor {

    private final String id;
    private final ScreenMatcher matcher;
    private final List<Action> actions;
    private final int maxTimes;
    private final long cooldownMs;

    Interceptor(String id, Builder b, FlowDefaults defaults) {
        this.id = id;
        this.matcher = b.matcher;
        this.actions = Collections.unmodifiableList(new ArrayList<>(b.actions));
        this.maxTimes = b.maxTimes != null ? b.maxTimes : defaults.getInterceptorMaxTimes();
        this.cooldownMs = b.cooldownMs != null ? b.cooldownMs : defaults.getInterceptorCooldownMs();
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

    /** 一次运行中最多触发几次，用完后不再检查。 */
    public int getMaxTimes() {
        return maxTimes;
    }

    public long getCooldownMs() {
        return cooldownMs;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Interceptor)) {
            return false;
        }
        Interceptor i = (Interceptor) o;
        return id.equals(i.id) && Objects.equals(matcher, i.matcher) && actions.equals(i.actions)
                && maxTimes == i.maxTimes && cooldownMs == i.cooldownMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, matcher, actions, maxTimes, cooldownMs);
    }

    @Override
    public String toString() {
        return "Interceptor{" + id + ", match=" + matcher + ", actions=" + actions + "}";
    }

    public static final class Builder {

        private ScreenMatcher matcher;
        private final List<Action> actions = new ArrayList<>();
        private Integer maxTimes;
        private Long cooldownMs;

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

        public Builder maxTimes(int value) {
            maxTimes = value;
            return this;
        }

        public Builder cooldownMs(long value) {
            cooldownMs = value;
            return this;
        }

        ScreenMatcher matcher() {
            return matcher;
        }

        List<Action> actionList() {
            return actions;
        }

        Integer maxTimesValue() {
            return maxTimes;
        }

        Long cooldownValue() {
            return cooldownMs;
        }
    }
}

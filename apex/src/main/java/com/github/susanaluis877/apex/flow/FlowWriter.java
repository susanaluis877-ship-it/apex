package com.github.susanaluis877.apex.flow;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.action.Actions;
import com.github.susanaluis877.apex.action.Coord;
import com.github.susanaluis877.apex.match.MatchJson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 {@link Flow} 写成 JSON，格式与 {@link FlowParser} 相同，用于把 Java DSL 写的流程转成 JSON（远程下发、放进 assets）。
 *
 * <p>和默认值相同的设置不写出来；状态和拦截器的超时、重试等与流程 {@code defaults} 相同时也省略。
 * 内联的 {@code custom(screen -> ...)} 条件和 {@code run(ctx -> ...)} 动作是代码，写不成 JSON：
 * 需要先用 {@code Apex.registerMatcher} / {@code registerAction} 注册，改成按名字引用。
 */
public final class FlowWriter {

    private FlowWriter() {
    }

    /**
     * 写成 JSON 并确认解析回来与原流程相等。
     *
     * @throws IllegalArgumentException 流程里有内联代码，消息里说明在哪个状态、哪个动作
     */
    public static String toJson(Flow flow) {
        try {
            JSONObject json = write(flow);
            String text = json.toString(2);
            Flow back;
            try {
                back = FlowParser.parse(text);
            } catch (FlowParseException e) {
                throw new IllegalStateException("written JSON does not parse: " + e.getMessage(), e);
            }
            if (!back.equals(flow)) {
                throw new IllegalStateException("written JSON parses into a different flow");
            }
            return text;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 写成 JSON 对象，不做往返校验。 */
    public static JSONObject write(Flow flow) throws JSONException {
        FlowDefaults d = flow.getDefaults();
        JSONObject o = new JSONObject();
        o.put("schema", FlowParser.SCHEMA_VERSION);
        o.put("id", flow.getId());
        o.put("start", flow.getStart());
        if (flow.getTimeoutMs() != Flow.DEFAULT_TIMEOUT_MS) {
            o.put("timeoutMs", flow.getTimeoutMs());
        }
        if (flow.getMaxSteps() != Flow.DEFAULT_MAX_STEPS) {
            o.put("maxSteps", flow.getMaxSteps());
        }
        if (flow.getMaxInterceptions() != Flow.DEFAULT_MAX_INTERCEPTIONS) {
            o.put("maxInterceptions", flow.getMaxInterceptions());
        }
        JSONObject defaults = new JSONObject();
        putIf(defaults, "timeoutMs", d.getTimeoutMs(), FlowDefaults.DEFAULT_STATE_TIMEOUT_MS);
        putIf(defaults, "retryIntervalMs", d.getRetryIntervalMs(), FlowDefaults.DEFAULT_RETRY_INTERVAL_MS);
        putIf(defaults, "maxRetries", d.getMaxRetries(), FlowDefaults.DEFAULT_MAX_RETRIES);
        putIf(defaults, "actionGapMs", d.getActionGapMs(), FlowDefaults.DEFAULT_ACTION_GAP_MS);
        putIf(defaults, "interceptorMaxTimes", d.getInterceptorMaxTimes(), FlowDefaults.DEFAULT_INTERCEPTOR_MAX_TIMES);
        putIf(defaults, "interceptorCooldownMs", d.getInterceptorCooldownMs(),
                FlowDefaults.DEFAULT_INTERCEPTOR_COOLDOWN_MS);
        if (defaults.length() > 0) {
            o.put("defaults", defaults);
        }
        if (!flow.isUseGlobalInterceptors()) {
            o.put("useGlobalInterceptors", false);
        }
        if (!flow.getExcludedGlobalInterceptors().isEmpty()) {
            o.put("excludeGlobalInterceptors", new JSONArray(flow.getExcludedGlobalInterceptors()));
        }
        JSONArray states = new JSONArray();
        for (State state : flow.getStates()) {
            states.put(writeState(state, d));
        }
        o.put("states", states);
        if (!flow.getInterceptors().isEmpty()) {
            JSONArray interceptors = new JSONArray();
            for (Interceptor interceptor : flow.getInterceptors()) {
                interceptors.put(writeInterceptor(interceptor, d));
            }
            o.put("interceptors", interceptors);
        }
        return o;
    }

    private static JSONObject writeState(State state, FlowDefaults d) throws JSONException {
        String where = "state '" + state.getId() + "'";
        JSONObject o = new JSONObject();
        o.put("id", state.getId());
        o.put("match", matcher(state, where));
        if (!state.getActions().isEmpty()) {
            o.put("actions", actions(state.getActions(), where));
        }
        if (!state.getNext().isEmpty()) {
            o.put("next", new JSONArray(state.getNext()));
        }
        if (state.isTerminal()) {
            o.put("end", state.getEnd().name().toLowerCase(Locale.ROOT));
        }
        putIf(o, "timeoutMs", state.getTimeoutMs(), d.getTimeoutMs());
        putIf(o, "retryIntervalMs", state.getRetryIntervalMs(), d.getRetryIntervalMs());
        putIf(o, "maxRetries", state.getMaxRetries(), d.getMaxRetries());
        return o;
    }

    private static JSONObject writeInterceptor(Interceptor interceptor, FlowDefaults d) throws JSONException {
        String where = "interceptor '" + interceptor.getId() + "'";
        JSONObject o = new JSONObject();
        o.put("id", interceptor.getId());
        try {
            o.put("match", MatchJson.write(interceptor.getMatcher()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + " match: " + e.getMessage(), e);
        }
        o.put("actions", actions(interceptor.getActions(), where));
        putIf(o, "maxTimes", interceptor.getMaxTimes(), d.getInterceptorMaxTimes());
        putIf(o, "cooldownMs", interceptor.getCooldownMs(), d.getInterceptorCooldownMs());
        return o;
    }

    private static JSONObject matcher(State state, String where) throws JSONException {
        try {
            return MatchJson.write(state.getMatcher());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + " match: " + e.getMessage(), e);
        }
    }

    private static JSONArray actions(List<Action> actions, String where) throws JSONException {
        JSONArray array = new JSONArray();
        for (int i = 0; i < actions.size(); i++) {
            try {
                array.put(action(actions.get(i)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + " action " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return array;
    }

    static JSONObject action(Action action) throws JSONException {
        JSONObject o = new JSONObject();
        if (action instanceof Action.Click) {
            Action.Click a = (Action.Click) action;
            o.put("type", a.isLongClick() ? "longClick" : "click");
            o.put("target", MatchJson.write(a.getTarget()));
            if (a.getMode() != Action.ClickMode.AUTO) {
                o.put("mode", lower(a.getMode()));
            }
        } else if (action instanceof Action.Tap) {
            Action.Tap a = (Action.Tap) action;
            o.put("type", "tap");
            Coord p = a.getPoint();
            if (p.isRatio()) {
                o.put("xRatio", p.getX()).put("yRatio", p.getY());
            } else {
                o.put("x", (long) p.getX()).put("y", (long) p.getY());
            }
            putIf(o, "durationMs", a.getDurationMs(), Actions.DEFAULT_TAP_MS);
        } else if (action instanceof Action.Swipe) {
            Action.Swipe a = (Action.Swipe) action;
            o.put("type", "swipe");
            boolean ratio = a.getFrom().isRatio();
            if (ratio != a.getTo().isRatio()) {
                throw new IllegalArgumentException("swipe mixes pixel and ratio coordinates, which JSON cannot express");
            }
            o.put(ratio ? "fromRatio" : "from", pair(a.getFrom()));
            o.put(ratio ? "toRatio" : "to", pair(a.getTo()));
            putIf(o, "durationMs", a.getDurationMs(), Actions.DEFAULT_SWIPE_MS);
        } else if (action instanceof Action.Scroll) {
            Action.Scroll a = (Action.Scroll) action;
            o.put("type", "scroll");
            if (a.getTarget() != null) {
                o.put("target", MatchJson.write(a.getTarget()));
            }
            o.put("direction", lower(a.getDirection()));
        } else if (action instanceof Action.ScrollTo) {
            Action.ScrollTo a = (Action.ScrollTo) action;
            o.put("type", "scrollTo");
            o.put("target", MatchJson.write(a.getTarget()));
            if (a.getContainer() != null) {
                o.put("container", MatchJson.write(a.getContainer()));
            }
            if (a.getDirection() != Action.Direction.FORWARD) {
                o.put("direction", lower(a.getDirection()));
            }
            putIf(o, "maxSwipes", a.getMaxSwipes(), Actions.DEFAULT_SCROLL_TO_MAX_SWIPES);
        } else if (action instanceof Action.SetText) {
            Action.SetText a = (Action.SetText) action;
            o.put("type", "setText").put("target", MatchJson.write(a.getTarget())).put("text", a.getText());
        } else if (action instanceof Action.SetProgress) {
            Action.SetProgress a = (Action.SetProgress) action;
            o.put("type", "setProgress").put("target", MatchJson.write(a.getTarget()));
            if (a.getValue() != null) {
                o.put("value", a.getValue().doubleValue());
            } else {
                o.put("ratio", a.getRatio().doubleValue());
            }
        } else if (action instanceof Action.Global) {
            o.put("type", lower(((Action.Global) action).getKind()));
        } else if (action instanceof Action.LaunchApp) {
            o.put("type", "launchApp").put("package", ((Action.LaunchApp) action).getPackageName());
        } else if (action instanceof Action.Sleep) {
            o.put("type", "sleep").put("ms", ((Action.Sleep) action).getMs());
        } else if (action instanceof Action.Custom) {
            Action.Custom a = (Action.Custom) action;
            o.put("type", "custom").put("name", a.getName());
            if (!a.getArgs().isEmpty()) {
                o.put("args", new JSONObject((Map<?, ?>) a.getArgs()));
            }
        } else if (action instanceof Action.Inline) {
            throw new IllegalArgumentException("run(ctx -> ...) cannot be written as JSON; register it with "
                    + "Apex.registerAction and reference it with Actions.custom(name)");
        } else {
            throw new IllegalArgumentException("unsupported action " + action);
        }
        if (action.isOptional()) {
            o.put("optional", true);
        }
        if (action.getDelayMs() > 0) {
            o.put("delayMs", action.getDelayMs());
        }
        return o;
    }

    private static JSONArray pair(Coord c) throws JSONException {
        return c.isRatio() ? new JSONArray().put(c.getX()).put(c.getY())
                : new JSONArray().put((long) c.getX()).put((long) c.getY());
    }

    private static void putIf(JSONObject o, String key, long value, long defaultValue) throws JSONException {
        if (value != defaultValue) {
            o.put(key, value);
        }
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }
}

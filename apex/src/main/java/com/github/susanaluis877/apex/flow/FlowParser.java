package com.github.susanaluis877.apex.flow;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.action.Actions;
import com.github.susanaluis877.apex.action.Coord;
import com.github.susanaluis877.apex.flow.FlowValidationException.Problem;
import com.github.susanaluis877.apex.match.By;
import com.github.susanaluis877.apex.match.Match;
import com.github.susanaluis877.apex.match.ScreenMatcher;
import com.github.susanaluis877.apex.match.Selector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.PatternSyntaxException;

/**
 * 把 JSON 流程定义解析成 {@link Flow}。解析结果与用 {@link Flow.Builder} 写出的同一个流程相等。
 *
 * <p>格式要点：
 * <ul>
 *   <li>任何层级出现未知的键都会报错，用来发现拼写错误。</li>
 *   <li>matcher 对象里的多个键是 AND 关系；selector 对象里的多个键也是 AND 关系。</li>
 *   <li>{@code next} 可以是字符串或字符串数组；{@code end} 取 {@code "success"} 或 {@code "failure"}。</li>
 *   <li>值为 {@code null} 的键视为没写。</li>
 * </ul>
 * 完整格式见 README。
 */
public final class FlowParser {

    /** 当前支持的格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    private static final String[] MATCHER_KEYS =
            {"package", "activity", "has", "not", "allOf", "anyOf", "always", "custom"};

    private FlowParser() {
    }

    /**
     * @throws FlowParseException JSON 语法错误、字段类型或取值错误、流程校验不通过时
     */
    public static Flow parse(String json) throws FlowParseException {
        if (json == null) {
            throw new FlowParseException("", "json is null");
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new FlowParseException("", "invalid JSON: " + e.getMessage(), e);
        }
        return parseFlow(root);
    }

    private static Flow parseFlow(JSONObject o) throws FlowParseException {
        checkKeys(o, "", "schema", "id", "start", "timeoutMs", "maxSteps", "maxInterceptions",
                "defaults", "states", "interceptors");
        Long schema = optLong(o, "schema", "");
        if (schema != null && schema != SCHEMA_VERSION) {
            throw new FlowParseException("schema",
                    "unsupported schema " + schema + " (supported: " + SCHEMA_VERSION + ")");
        }
        Flow.Builder b = new Flow.Builder(requireString(o, "id", ""));
        String start = optString(o, "start", "");
        if (start != null) {
            b.start(start);
        }
        Long timeoutMs = optLong(o, "timeoutMs", "");
        if (timeoutMs != null) {
            b.timeoutMs(timeoutMs);
        }
        Integer maxSteps = optInt(o, "maxSteps", "");
        if (maxSteps != null) {
            b.maxSteps(maxSteps);
        }
        Integer maxInterceptions = optInt(o, "maxInterceptions", "");
        if (maxInterceptions != null) {
            b.maxInterceptions(maxInterceptions);
        }
        JSONObject defaults = optObject(o, "defaults", "");
        if (defaults != null) {
            parseDefaults(defaults, b);
        }

        JSONArray states = optArray(o, "states", "");
        if (states == null) {
            throw new FlowParseException("states", "required");
        }
        for (int i = 0; i < states.length(); i++) {
            parseState(element(states, i, "states"), "states[" + i + "]", b);
        }
        JSONArray interceptors = optArray(o, "interceptors", "");
        if (interceptors != null) {
            for (int i = 0; i < interceptors.length(); i++) {
                parseInterceptor(element(interceptors, i, "interceptors"), "interceptors[" + i + "]", b);
            }
        }

        try {
            return b.build();
        } catch (FlowValidationException e) {
            List<Problem> problems = e.getProblems();
            StringBuilder reason = new StringBuilder(problems.get(0).getMessage());
            for (int i = 1; i < problems.size(); i++) {
                Problem p = problems.get(i);
                String path = pathOf(p);
                reason.append("; ").append(path.isEmpty() ? "(root)" : path).append(": ").append(p.getMessage());
            }
            throw new FlowParseException(pathOf(problems.get(0)), reason.toString(), e);
        }
    }

    private static void parseDefaults(JSONObject o, Flow.Builder b) throws FlowParseException {
        String path = "defaults";
        checkKeys(o, path, "timeoutMs", "retryIntervalMs", "maxRetries", "actionGapMs",
                "interceptorMaxTimes", "interceptorCooldownMs");
        Long timeoutMs = optLong(o, "timeoutMs", path);
        Long retryIntervalMs = optLong(o, "retryIntervalMs", path);
        Integer maxRetries = optInt(o, "maxRetries", path);
        Long actionGapMs = optLong(o, "actionGapMs", path);
        Integer interceptorMaxTimes = optInt(o, "interceptorMaxTimes", path);
        Long interceptorCooldownMs = optLong(o, "interceptorCooldownMs", path);
        b.defaults(d -> {
            if (timeoutMs != null) {
                d.timeoutMs(timeoutMs);
            }
            if (retryIntervalMs != null) {
                d.retryIntervalMs(retryIntervalMs);
            }
            if (maxRetries != null) {
                d.maxRetries(maxRetries);
            }
            if (actionGapMs != null) {
                d.actionGapMs(actionGapMs);
            }
            if (interceptorMaxTimes != null) {
                d.interceptorMaxTimes(interceptorMaxTimes);
            }
            if (interceptorCooldownMs != null) {
                d.interceptorCooldownMs(interceptorCooldownMs);
            }
        });
    }

    private static void parseState(JSONObject o, String path, Flow.Builder b) throws FlowParseException {
        checkKeys(o, path, "id", "match", "actions", "next", "end", "timeoutMs", "retryIntervalMs", "maxRetries");
        String id = optString(o, "id", path);
        Object match = opt(o, "match");
        ScreenMatcher matcher = match != null ? parseMatcher(match, join(path, "match")) : null;
        List<Action> actions = parseActions(o, path);
        List<String> next = parseNext(o, path);
        String endValue = optString(o, "end", path);
        State.End end = State.End.NONE;
        if (endValue != null) {
            switch (endValue) {
                case "success":
                    end = State.End.SUCCESS;
                    break;
                case "failure":
                    end = State.End.FAILURE;
                    break;
                default:
                    throw new FlowParseException(join(path, "end"),
                            "expected \"success\" or \"failure\", got \"" + endValue + "\"");
            }
        }
        Long timeoutMs = optLong(o, "timeoutMs", path);
        Long retryIntervalMs = optLong(o, "retryIntervalMs", path);
        Integer maxRetries = optInt(o, "maxRetries", path);
        State.End finalEnd = end;
        b.state(id, s -> {
            s.match(matcher).actions(actions).next(next.toArray(new String[0])).end(finalEnd);
            if (timeoutMs != null) {
                s.timeoutMs(timeoutMs);
            }
            if (retryIntervalMs != null) {
                s.retryIntervalMs(retryIntervalMs);
            }
            if (maxRetries != null) {
                s.maxRetries(maxRetries);
            }
        });
    }

    private static void parseInterceptor(JSONObject o, String path, Flow.Builder b) throws FlowParseException {
        checkKeys(o, path, "id", "match", "actions", "maxTimes", "cooldownMs");
        String id = optString(o, "id", path);
        Object match = opt(o, "match");
        ScreenMatcher matcher = match != null ? parseMatcher(match, join(path, "match")) : null;
        List<Action> actions = parseActions(o, path);
        Integer maxTimes = optInt(o, "maxTimes", path);
        Long cooldownMs = optLong(o, "cooldownMs", path);
        b.interceptor(id, i -> {
            i.match(matcher).actions(actions);
            if (maxTimes != null) {
                i.maxTimes(maxTimes);
            }
            if (cooldownMs != null) {
                i.cooldownMs(cooldownMs);
            }
        });
    }

    private static List<String> parseNext(JSONObject o, String path) throws FlowParseException {
        Object v = opt(o, "next");
        String p = join(path, "next");
        if (v == null) {
            return Collections.emptyList();
        }
        if (v instanceof String) {
            return Collections.singletonList((String) v);
        }
        if (!(v instanceof JSONArray)) {
            throw new FlowParseException(p, "expected string or array of strings");
        }
        JSONArray array = (JSONArray) v;
        List<String> next = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String)) {
                throw new FlowParseException(p + "[" + i + "]", "expected string");
            }
            next.add((String) item);
        }
        return next;
    }

    // ---- matcher ----

    private static ScreenMatcher parseMatcher(Object v, String path) throws FlowParseException {
        if (!(v instanceof JSONObject)) {
            throw new FlowParseException(path, "expected object");
        }
        JSONObject o = (JSONObject) v;
        checkKeys(o, path, MATCHER_KEYS);
        List<ScreenMatcher> parts = new ArrayList<>();
        for (String key : MATCHER_KEYS) {
            Object value = opt(o, key);
            if (value == null) {
                continue;
            }
            String p = join(path, key);
            switch (key) {
                case "package":
                    parts.add(Match.pkg(asString(value, p)));
                    break;
                case "activity":
                    parts.add(Match.activity(asString(value, p)));
                    break;
                case "has":
                    if (value instanceof JSONArray) {
                        JSONArray array = (JSONArray) value;
                        if (array.length() == 0) {
                            throw new FlowParseException(p, "expected at least one selector");
                        }
                        for (int i = 0; i < array.length(); i++) {
                            parts.add(Match.has(parseSelector(array.opt(i), p + "[" + i + "]")));
                        }
                    } else {
                        parts.add(Match.has(parseSelector(value, p)));
                    }
                    break;
                case "not":
                    parts.add(Match.not(parseMatcher(value, p)));
                    break;
                case "allOf":
                case "anyOf":
                    if (!(value instanceof JSONArray) || ((JSONArray) value).length() == 0) {
                        throw new FlowParseException(p, "expected non-empty array of matchers");
                    }
                    JSONArray array = (JSONArray) value;
                    List<ScreenMatcher> items = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        items.add(parseMatcher(array.opt(i), p + "[" + i + "]"));
                    }
                    parts.add(key.equals("allOf") ? Match.allOf(items) : Match.anyOf(items));
                    break;
                case "always":
                    if (!Boolean.TRUE.equals(value)) {
                        throw new FlowParseException(p, "expected true");
                    }
                    parts.add(Match.always());
                    break;
                case "custom":
                    parts.add(parseCustomMatcher(value, p));
                    break;
                default:
                    throw new AssertionError(key);
            }
        }
        if (parts.isEmpty()) {
            throw new FlowParseException(path, "matcher needs at least one condition");
        }
        return Match.allOf(parts);
    }

    private static ScreenMatcher parseCustomMatcher(Object v, String path) throws FlowParseException {
        if (v instanceof String) {
            return Match.custom((String) v);
        }
        if (!(v instanceof JSONObject)) {
            throw new FlowParseException(path, "expected name string or {\"name\", \"args\"} object");
        }
        JSONObject o = (JSONObject) v;
        checkKeys(o, path, "name", "args");
        return Match.custom(requireString(o, "name", path), parseArgs(o, path));
    }

    private static Selector parseSelector(Object v, String path) throws FlowParseException {
        if (!(v instanceof JSONObject)) {
            throw new FlowParseException(path, "expected selector object");
        }
        JSONObject o = (JSONObject) v;
        checkKeys(o, path, "text", "textContains", "textMatches", "desc", "descContains", "descMatches",
                "id", "className", "clickable", "enabled", "checked", "selected", "scrollable", "visible");
        Selector s = null;
        String value;
        if ((value = optString(o, "text", path)) != null) {
            s = s == null ? By.text(value) : s.text(value);
        }
        if ((value = optString(o, "textContains", path)) != null) {
            s = s == null ? By.textContains(value) : s.textContains(value);
        }
        if ((value = optString(o, "textMatches", path)) != null) {
            try {
                s = s == null ? By.textMatches(value) : s.textMatches(value);
            } catch (PatternSyntaxException e) {
                throw new FlowParseException(join(path, "textMatches"), "invalid regex: " + e.getDescription());
            }
        }
        if ((value = optString(o, "desc", path)) != null) {
            s = s == null ? By.desc(value) : s.desc(value);
        }
        if ((value = optString(o, "descContains", path)) != null) {
            s = s == null ? By.descContains(value) : s.descContains(value);
        }
        if ((value = optString(o, "descMatches", path)) != null) {
            try {
                s = s == null ? By.descMatches(value) : s.descMatches(value);
            } catch (PatternSyntaxException e) {
                throw new FlowParseException(join(path, "descMatches"), "invalid regex: " + e.getDescription());
            }
        }
        if ((value = optString(o, "id", path)) != null) {
            s = s == null ? By.id(value) : s.id(value);
        }
        if ((value = optString(o, "className", path)) != null) {
            s = s == null ? By.className(value) : s.className(value);
        }
        Boolean flag;
        if ((flag = optBoolean(o, "clickable", path)) != null) {
            s = s == null ? By.clickable(flag) : s.clickable(flag);
        }
        if ((flag = optBoolean(o, "enabled", path)) != null) {
            s = s == null ? By.enabled(flag) : s.enabled(flag);
        }
        if ((flag = optBoolean(o, "checked", path)) != null) {
            s = s == null ? By.checked(flag) : s.checked(flag);
        }
        if ((flag = optBoolean(o, "selected", path)) != null) {
            s = s == null ? By.selected(flag) : s.selected(flag);
        }
        if ((flag = optBoolean(o, "scrollable", path)) != null) {
            s = s == null ? By.scrollable(flag) : s.scrollable(flag);
        }
        if (s == null) {
            throw new FlowParseException(path, "selector needs at least one condition besides visible");
        }
        if ((flag = optBoolean(o, "visible", path)) != null) {
            s = s.visible(flag);
        }
        return s;
    }

    // ---- actions ----

    private static List<Action> parseActions(JSONObject o, String path) throws FlowParseException {
        JSONArray array = optArray(o, "actions", path);
        if (array == null) {
            return Collections.emptyList();
        }
        List<Action> actions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String p = join(path, "actions") + "[" + i + "]";
            actions.add(parseAction(element(array, i, join(path, "actions")), p));
        }
        return actions;
    }

    private static Action parseAction(JSONObject o, String path) throws FlowParseException {
        String type = requireString(o, "type", path);
        Action action;
        try {
            action = parseActionBody(o, type, path);
        } catch (IllegalArgumentException e) {
            throw new FlowParseException(path, e.getMessage());
        }
        Boolean optional = optBoolean(o, "optional", path);
        if (Boolean.TRUE.equals(optional)) {
            action = action.optional();
        }
        Long delayMs = optLong(o, "delayMs", path);
        if (delayMs != null) {
            if (delayMs < 0) {
                throw new FlowParseException(join(path, "delayMs"), "must be >= 0");
            }
            action = action.delayMs(delayMs);
        }
        return action;
    }

    private static Action parseActionBody(JSONObject o, String type, String path) throws FlowParseException {
        switch (type) {
            case "click":
            case "longClick": {
                checkActionKeys(o, path, "target", "mode");
                Selector target = parseSelector(requireValue(o, "target", path), join(path, "target"));
                Action.ClickMode mode = parseEnum(o, "mode", path, Action.ClickMode.class, Action.ClickMode.AUTO);
                return type.equals("click") ? Actions.click(target, mode) : Actions.longClick(target, mode);
            }
            case "tap": {
                checkActionKeys(o, path, "x", "y", "xRatio", "yRatio", "durationMs");
                Coord point;
                if (o.has("x") || o.has("y")) {
                    if (o.has("xRatio") || o.has("yRatio")) {
                        throw new FlowParseException(path, "use either x/y or xRatio/yRatio, not both");
                    }
                    point = Coord.px(requireInt(o, "x", path), requireInt(o, "y", path));
                } else {
                    point = Coord.ratio(requireDouble(o, "xRatio", path), requireDouble(o, "yRatio", path));
                }
                Long duration = optLong(o, "durationMs", path);
                return Actions.tap(point, duration != null ? duration : Actions.DEFAULT_TAP_MS);
            }
            case "swipe": {
                checkActionKeys(o, path, "from", "to", "fromRatio", "toRatio", "durationMs");
                Coord from;
                Coord to;
                if (o.has("from") || o.has("to")) {
                    if (o.has("fromRatio") || o.has("toRatio")) {
                        throw new FlowParseException(path, "use either from/to or fromRatio/toRatio, not both");
                    }
                    double[] f = pair(requireValue(o, "from", path), join(path, "from"));
                    double[] t = pair(requireValue(o, "to", path), join(path, "to"));
                    from = Coord.px(toIntExact(f[0], join(path, "from")), toIntExact(f[1], join(path, "from")));
                    to = Coord.px(toIntExact(t[0], join(path, "to")), toIntExact(t[1], join(path, "to")));
                } else {
                    double[] f = pair(requireValue(o, "fromRatio", path), join(path, "fromRatio"));
                    double[] t = pair(requireValue(o, "toRatio", path), join(path, "toRatio"));
                    from = Coord.ratio(f[0], f[1]);
                    to = Coord.ratio(t[0], t[1]);
                }
                Long duration = optLong(o, "durationMs", path);
                return Actions.swipe(from, to, duration != null ? duration : Actions.DEFAULT_SWIPE_MS);
            }
            case "scroll": {
                checkActionKeys(o, path, "target", "direction");
                Object target = opt(o, "target");
                Action.Direction direction = parseEnum(o, "direction", path, Action.Direction.class, null);
                if (direction == null) {
                    throw new FlowParseException(join(path, "direction"), "required");
                }
                return target == null
                        ? Actions.scroll(direction)
                        : Actions.scroll(parseSelector(target, join(path, "target")), direction);
            }
            case "setText": {
                checkActionKeys(o, path, "target", "text");
                Selector target = parseSelector(requireValue(o, "target", path), join(path, "target"));
                return Actions.setText(target, requireString(o, "text", path));
            }
            case "back":
                checkActionKeys(o, path);
                return Actions.back();
            case "home":
                checkActionKeys(o, path);
                return Actions.home();
            case "recents":
                checkActionKeys(o, path);
                return Actions.recents();
            case "notifications":
                checkActionKeys(o, path);
                return Actions.notifications();
            case "launchApp":
                checkActionKeys(o, path, "package");
                return Actions.launchApp(requireString(o, "package", path));
            case "sleep":
                checkActionKeys(o, path, "ms");
                return Actions.sleep(requireLong(o, "ms", path));
            case "custom":
                checkActionKeys(o, path, "name", "args");
                return Actions.custom(requireString(o, "name", path), parseArgs(o, path));
            default:
                throw new FlowParseException(join(path, "type"), "unknown action type '" + type + "'");
        }
    }

    private static void checkActionKeys(JSONObject o, String path, String... keys) throws FlowParseException {
        List<String> allowed = new ArrayList<>(Arrays.asList(keys));
        allowed.add("type");
        allowed.add("optional");
        allowed.add("delayMs");
        checkKeys(o, path, allowed.toArray(new String[0]));
    }

    private static Map<String, String> parseArgs(JSONObject o, String path) throws FlowParseException {
        JSONObject args = optObject(o, "args", path);
        if (args == null) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : sortedKeys(args)) {
            Object v = opt(args, key);
            String p = join(join(path, "args"), key);
            if (v == null) {
                continue;
            }
            if (v instanceof String) {
                result.put(key, (String) v);
            } else if (v instanceof Boolean) {
                result.put(key, v.toString());
            } else if (v instanceof Number) {
                result.put(key, numberToString((Number) v));
            } else {
                throw new FlowParseException(p, "args values must be string, number or boolean");
            }
        }
        return result;
    }

    // ---- helpers ----

    private static String pathOf(Problem p) {
        String base;
        switch (p.getScope()) {
            case Problem.STATE:
                base = "states[" + p.getOrdinal() + "]";
                break;
            case Problem.INTERCEPTOR:
                base = "interceptors[" + p.getOrdinal() + "]";
                break;
            default:
                base = "";
        }
        String path = p.getField() != null ? join(base, p.getField()) : base;
        return p.getIndex() >= 0 ? path + "[" + p.getIndex() + "]" : path;
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static void checkKeys(JSONObject o, String path, String... allowed) throws FlowParseException {
        Set<String> allowedSet = new HashSet<>(Arrays.asList(allowed));
        for (String key : sortedKeys(o)) {
            if (!allowedSet.contains(key)) {
                throw new FlowParseException(join(path, key), "unknown key (allowed: "
                        + String.join(", ", allowed) + ")");
            }
        }
    }

    private static Set<String> sortedKeys(JSONObject o) {
        Set<String> keys = new TreeSet<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        return keys;
    }

    /** 返回键对应的值；不存在或显式写了 null 时返回 null。 */
    private static Object opt(JSONObject o, String key) {
        Object v = o.opt(key);
        return v == null || v == JSONObject.NULL ? null : v;
    }

    private static Object requireValue(JSONObject o, String key, String path) throws FlowParseException {
        Object v = opt(o, key);
        if (v == null) {
            throw new FlowParseException(join(path, key), "required");
        }
        return v;
    }

    private static JSONObject element(JSONArray array, int i, String path) throws FlowParseException {
        Object v = array.opt(i);
        if (!(v instanceof JSONObject)) {
            throw new FlowParseException(path + "[" + i + "]", "expected object");
        }
        return (JSONObject) v;
    }

    private static String asString(Object v, String path) throws FlowParseException {
        if (!(v instanceof String)) {
            throw new FlowParseException(path, "expected string");
        }
        return (String) v;
    }

    private static String optString(JSONObject o, String key, String path) throws FlowParseException {
        Object v = opt(o, key);
        return v == null ? null : asString(v, join(path, key));
    }

    private static String requireString(JSONObject o, String key, String path) throws FlowParseException {
        return asString(requireValue(o, key, path), join(path, key));
    }

    private static Boolean optBoolean(JSONObject o, String key, String path) throws FlowParseException {
        Object v = opt(o, key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Boolean)) {
            throw new FlowParseException(join(path, key), "expected boolean");
        }
        return (Boolean) v;
    }

    private static Long optLong(JSONObject o, String key, String path) throws FlowParseException {
        Object v = opt(o, key);
        return v == null ? null : asLong(v, join(path, key));
    }

    private static long requireLong(JSONObject o, String key, String path) throws FlowParseException {
        return asLong(requireValue(o, key, path), join(path, key));
    }

    private static Integer optInt(JSONObject o, String key, String path) throws FlowParseException {
        Long v = optLong(o, key, path);
        if (v == null) {
            return null;
        }
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new FlowParseException(join(path, key), "integer out of range");
        }
        return v.intValue();
    }

    private static int requireInt(JSONObject o, String key, String path) throws FlowParseException {
        long v = requireLong(o, key, path);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new FlowParseException(join(path, key), "integer out of range");
        }
        return (int) v;
    }

    private static double requireDouble(JSONObject o, String key, String path) throws FlowParseException {
        Object v = requireValue(o, key, path);
        if (!(v instanceof Number)) {
            throw new FlowParseException(join(path, key), "expected number");
        }
        return ((Number) v).doubleValue();
    }

    private static long asLong(Object v, String path) throws FlowParseException {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigInteger) {
            // longValueExact() 在 Android 上要求 API 31
            if (((BigInteger) v).bitLength() > 63) {
                throw new FlowParseException(path, "integer out of range");
            }
            return ((BigInteger) v).longValue();
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0e15) {
                return (long) d;
            }
        }
        throw new FlowParseException(path, "expected integer");
    }

    private static int toIntExact(double v, String path) throws FlowParseException {
        if (v != Math.rint(v) || v < 0 || v > Integer.MAX_VALUE) {
            throw new FlowParseException(path, "expected non-negative integer pixel coordinates");
        }
        return (int) v;
    }

    private static double[] pair(Object v, String path) throws FlowParseException {
        if (!(v instanceof JSONArray) || ((JSONArray) v).length() != 2) {
            throw new FlowParseException(path, "expected [x, y]");
        }
        JSONArray array = (JSONArray) v;
        double[] result = new double[2];
        for (int i = 0; i < 2; i++) {
            Object item = array.opt(i);
            if (!(item instanceof Number)) {
                throw new FlowParseException(path + "[" + i + "]", "expected number");
            }
            result[i] = ((Number) item).doubleValue();
        }
        return result;
    }

    private static JSONObject optObject(JSONObject o, String key, String path) throws FlowParseException {
        Object v = opt(o, key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof JSONObject)) {
            throw new FlowParseException(join(path, key), "expected object");
        }
        return (JSONObject) v;
    }

    private static JSONArray optArray(JSONObject o, String key, String path) throws FlowParseException {
        Object v = opt(o, key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof JSONArray)) {
            throw new FlowParseException(join(path, key), "expected array");
        }
        return (JSONArray) v;
    }

    private static <E extends Enum<E>> E parseEnum(JSONObject o, String key, String path, Class<E> type,
                                                   E fallback) throws FlowParseException {
        String value = optString(o, key, path);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                names.add(constant.name().toLowerCase(Locale.ROOT));
            }
            throw new FlowParseException(join(path, key), "expected one of " + names + ", got \"" + value + "\"");
        }
    }

    private static String numberToString(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger) {
            return n.toString();
        }
        BigDecimal d = n instanceof BigDecimal ? (BigDecimal) n : new BigDecimal(n.toString());
        d = d.stripTrailingZeros();
        return d.scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
    }
}

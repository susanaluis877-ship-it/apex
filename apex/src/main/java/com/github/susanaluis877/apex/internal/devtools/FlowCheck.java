package com.github.susanaluis877.apex.internal.devtools;

import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.flow.Interceptor;
import com.github.susanaluis877.apex.flow.State;
import com.github.susanaluis877.apex.match.Match;
import com.github.susanaluis877.apex.match.ScreenMatcher;
import com.github.susanaluis877.apex.node.ScreenSnapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 拿流程的每个状态和拦截器去匹配一组页面样本，找出 matcher 写错的地方：
 * <ul>
 *   <li>{@code expected-mismatch}：某个状态（或拦截器）自己的样本没被它匹配上。</li>
 *   <li>{@code ambiguous} / {@code wrong-winner}：同一个 {@code next} 里有多个候选匹配同一个样本，
 *       引擎会进入排在前面的那个，可能不是想要的。</li>
 *   <li>{@code interceptor-on-state-page}：拦截器匹配到正常页面的样本。拦截器先于状态检查，会在那个页面误触发。</li>
 *   <li>{@code too-broad}：某个状态匹配所有样本。</li>
 *   <li>{@code unmatched}：样本没有被任何状态或拦截器匹配，可能缺状态，也可能只是过渡页。</li>
 * </ul>
 *
 * <p>样本名等于状态或拦截器的 id，或以 {@code id.}、{@code id#} 开头（同一页面的多张样本），就视为它的样本。
 * 自定义条件抛出的异常只记在对应的格子里，不影响其他结果。
 */
public final class FlowCheck {

    private FlowCheck() {
    }

    /**
     * @param explainAll 为 true 时给出每个不匹配格子的原因（检查实时页面时用）；
     *                   否则只给出样本所属状态或拦截器的不匹配原因，避免输出过长
     */
    public static JSONObject check(Flow flow, List<Interceptor> globals, Map<String, ScreenSnapshot> snapshots,
                                   boolean explainAll) throws JSONException {
        List<Interceptor> interceptors = flow.effectiveInterceptors(globals);
        Set<String> own = new HashSet<>();
        for (Interceptor interceptor : flow.getInterceptors()) {
            own.add(interceptor.getId());
        }
        Set<String> ids = new LinkedHashSet<>();
        for (State state : flow.getStates()) {
            ids.add(state.getId());
        }
        for (Interceptor interceptor : interceptors) {
            ids.add(interceptor.getId());
        }

        Map<String, String> labels = new LinkedHashMap<>();
        JSONArray samples = new JSONArray();
        for (Map.Entry<String, ScreenSnapshot> e : snapshots.entrySet()) {
            String label = labelOf(e.getKey(), ids);
            labels.put(e.getKey(), label);
            samples.put(new JSONObject().put("name", e.getKey()).putOpt("label", label)
                    .putOpt("pkg", e.getValue().getPackageName()).putOpt("activity", e.getValue().getActivity()));
        }

        List<String> warnings = new ArrayList<>();
        JSONArray warningList = new JSONArray();
        Map<String, Row> stateRows = new LinkedHashMap<>();
        for (State state : flow.getStates()) {
            stateRows.put(state.getId(), evaluate(state.getId(), state.getMatcher(), snapshots, labels, explainAll));
        }
        Map<String, Row> interceptorRows = new LinkedHashMap<>();
        for (Interceptor interceptor : interceptors) {
            interceptorRows.put(interceptor.getId(),
                    evaluate(interceptor.getId(), interceptor.getMatcher(), snapshots, labels, explainAll));
        }

        // 样本没被自己的状态或拦截器匹配
        for (Map.Entry<String, String> e : labels.entrySet()) {
            String name = e.getKey();
            String label = e.getValue();
            Row row = label == null ? null : stateRows.containsKey(label) ? stateRows.get(label)
                    : interceptorRows.get(label);
            if (row != null && !row.matches.contains(name)) {
                String why = row.errors.containsKey(name) ? "error: " + row.errors.get(name)
                        : row.mismatch.get(name);
                warn(warningList, warnings, "expected-mismatch", label, name, "sample '" + name + "' belongs to '"
                        + label + "' but '" + label + "' does not match it: " + why);
            }
        }
        // 拦截器先于状态检查，匹配到正常页面就会在那里误触发
        for (Interceptor interceptor : interceptors) {
            Row row = interceptorRows.get(interceptor.getId());
            for (String name : row.matches) {
                String label = labels.get(name);
                if (label != null && stateRows.containsKey(label)) {
                    warn(warningList, warnings, "interceptor-on-state-page", interceptor.getId(), name,
                            "interceptor '" + interceptor.getId() + "' matches '" + name + "' (a sample of state '"
                                    + label + "'); interceptors are checked before states, so it would fire there");
                }
            }
        }
        // 同一个 next 里多个候选匹配同一个样本时，排在前面的胜出
        for (State from : flow.getStates()) {
            if (from.isTerminal() || from.getNext().size() < 2) {
                continue;
            }
            for (String name : snapshots.keySet()) {
                List<String> matched = new ArrayList<>();
                for (String candidate : from.getNext()) {
                    Row row = stateRows.get(candidate);
                    if (row != null && row.matches.contains(name)) {
                        matched.add(candidate);
                    }
                }
                String label = labels.get(name);
                if (!matched.isEmpty() && label != null && from.getNext().contains(label)
                        && !matched.get(0).equals(label)) {
                    warn(warningList, warnings, "wrong-winner", from.getId(), name, "after '" + from.getId()
                            + "', sample '" + name + "' of '" + label + "' would enter '" + matched.get(0)
                            + "' instead, because '" + matched.get(0) + "' comes first in next " + from.getNext()
                            + " and also matches it");
                } else if (matched.size() >= 2) {
                    warn(warningList, warnings, "ambiguous", from.getId(), name, "after '" + from.getId()
                            + "', sample '" + name + "' matches " + matched + "; '" + matched.get(0)
                            + "' wins because it comes first in next");
                }
            }
        }
        if (snapshots.size() >= 3) {
            for (State state : flow.getStates()) {
                Row row = stateRows.get(state.getId());
                // always 是有意写的（例如起始状态先启动 App），不算太宽
                if (row.matches.size() == snapshots.size() && !state.getMatcher().equals(Match.always())) {
                    warn(warningList, warnings, "too-broad", row.id, null, "'" + row.id + "' matches all "
                            + snapshots.size() + " samples; its matcher is probably too broad");
                }
            }
        }
        for (String name : snapshots.keySet()) {
            boolean any = false;
            for (Row row : stateRows.values()) {
                any |= row.matches.contains(name);
            }
            for (Row row : interceptorRows.values()) {
                any |= row.matches.contains(name);
            }
            if (!any) {
                warn(warningList, warnings, "unmatched", null, name, "sample '" + name
                        + "' is matched by no state or interceptor (a transition page, or a missing state?)");
            }
        }

        JSONArray states = new JSONArray();
        for (State state : flow.getStates()) {
            JSONObject o = stateRows.get(state.getId()).toJson();
            o.put("end", state.isTerminal() ? state.getEnd().name().toLowerCase(Locale.ROOT) : JSONObject.NULL);
            o.put("next", new JSONArray(state.getNext()));
            states.put(o);
        }
        JSONArray interceptorList = new JSONArray();
        for (Interceptor interceptor : interceptors) {
            interceptorList.put(interceptorRows.get(interceptor.getId()).toJson()
                    .put("global", !own.contains(interceptor.getId())));
        }
        return new JSONObject().put("samples", samples).put("states", states).put("interceptors", interceptorList)
                .put("warnings", warningList);
    }

    /** 样本名对应的状态或拦截器 id；有多个前缀命中时取最长的。 */
    static String labelOf(String name, Set<String> ids) {
        String best = null;
        for (String id : ids) {
            boolean hit = name.equals(id) || name.startsWith(id + ".") || name.startsWith(id + "#");
            if (hit && (best == null || id.length() > best.length())) {
                best = id;
            }
        }
        return best;
    }

    private static Row evaluate(String id, ScreenMatcher matcher, Map<String, ScreenSnapshot> snapshots,
                                Map<String, String> labels, boolean explainAll) {
        Row row = new Row(id);
        for (Map.Entry<String, ScreenSnapshot> e : snapshots.entrySet()) {
            String name = e.getKey();
            try {
                if (matcher.matches(e.getValue())) {
                    row.matches.add(name);
                } else if (explainAll || id.equals(labels.get(name))) {
                    row.mismatch.put(name, matcher.explainMismatch(e.getValue()));
                }
            } catch (RuntimeException ex) {
                // 未注册或抛异常的自定义条件只影响这一格
                row.errors.put(name, ex.toString());
            }
        }
        return row;
    }

    private static void warn(JSONArray out, List<String> seen, String kind, String id, String sample, String message)
            throws JSONException {
        if (seen.contains(message)) {
            return;
        }
        seen.add(message);
        out.put(new JSONObject().put("kind", kind).putOpt("id", id).putOpt("sample", sample).put("message", message));
    }

    private static final class Row {
        final String id;
        final Set<String> matches = new LinkedHashSet<>();
        final Map<String, String> mismatch = new LinkedHashMap<>();
        final Map<String, String> errors = new LinkedHashMap<>();

        Row(String id) {
            this.id = id;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject().put("id", id).put("matches", new JSONArray(matches));
            if (!mismatch.isEmpty()) {
                o.put("mismatch", new JSONObject(mismatch));
            }
            if (!errors.isEmpty()) {
                o.put("errors", new JSONObject(errors));
            }
            return o;
        }
    }
}

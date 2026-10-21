package com.github.susanaluis877.apex.internal.devtools;

import com.github.susanaluis877.apex.RunHandle;
import com.github.susanaluis877.apex.RunResult;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.flow.FlowParseException;
import com.github.susanaluis877.apex.flow.FlowParser;
import com.github.susanaluis877.apex.flow.FlowValidationException;
import com.github.susanaluis877.apex.flow.FlowWriter;
import com.github.susanaluis877.apex.flow.Interceptor;
import com.github.susanaluis877.apex.flow.State;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.match.Selector;
import com.github.susanaluis877.apex.node.ScreenSnapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * devtools 协议：每个连接发一行 JSON 请求，收到一行或多行 JSON 响应。
 *
 * <pre>
 * 请求：{"cmd": "snapshot", ...参数}
 * 成功：{"ok": true, ...}
 * 失败：{"ok": false, "error": {"code": "BUSY", "message": "...", "path": "..."}}
 * run： 先逐行推送 {"ev": "trace", ...}，没有事件时每 2 秒一行 {"ev": "heartbeat"}，
 *       最后一行 {"ok": true, "ev": "result", "result": {...}}。推送失败（客户端断开）时取消运行。
 * </pre>
 *
 * 命令：info、snapshot、find、act、validate、check、run、export、cancel，参数见各处理方法。
 *
 * <p>validate、check、run、export 的流程可以是 JSON（{@code "flow": {...}}），也可以是宿主代码里用 Java DSL
 * 写的流程（{@code "flowRef": "com.foo.Flows#addAlarm"}，见 {@link FlowRefs}）。
 * 除了写响应失败，{@link #handle} 不抛异常。
 */
public final class Dispatcher {

    public static final int PROTOCOL = 1;

    static final long HEARTBEAT_MS = 2_000;
    private static final long MAX_WAIT_MS = 30_000;

    /** 响应输出，每次写一整行（不含换行符）。 */
    public interface LineSink {
        void write(String line) throws IOException;
    }

    private final Backend backend;

    public Dispatcher(Backend backend) {
        this.backend = backend;
    }

    public void handle(String line, LineSink out) throws IOException {
        JSONObject response;
        try {
            JSONObject request;
            try {
                request = new JSONObject(line);
            } catch (JSONException e) {
                throw new DevtoolsException(DevtoolsException.BAD_REQUEST, "request is not a JSON object: "
                        + e.getMessage());
            }
            Object cmd = request.opt("cmd");
            if (!(cmd instanceof String)) {
                throw new DevtoolsException(DevtoolsException.BAD_REQUEST, "missing \"cmd\"");
            }
            switch ((String) cmd) {
                case "info":
                    response = info();
                    break;
                case "snapshot":
                    response = snapshot(request);
                    break;
                case "find":
                    response = find(request);
                    break;
                case "act":
                    response = act(request);
                    break;
                case "validate":
                    response = validate(request);
                    break;
                case "check":
                    response = check(request);
                    break;
                case "run":
                    run(request, out);
                    return;
                case "export":
                    response = export(request);
                    break;
                case "cancel":
                    response = ok().put("cancelled", backend.cancelRunning());
                    break;
                default:
                    throw new DevtoolsException(DevtoolsException.BAD_REQUEST, "unknown cmd \"" + cmd
                            + "\" (supported: info, snapshot, find, act, validate, check, run, export, cancel)");
            }
        } catch (DevtoolsException e) {
            response = error(e.getCode(), e.getMessage(), e.getPath());
        } catch (JSONException | RuntimeException e) {
            response = error(DevtoolsException.INTERNAL, e.toString(), null);
        }
        out.write(response.toString());
    }

    /** {@code {"cmd": "info"}} */
    private JSONObject info() throws JSONException {
        JSONObject o = backend.info();
        JSONArray globals = new JSONArray();
        for (Interceptor interceptor : Registry.get().globalInterceptors()) {
            globals.put(interceptor.getId());
        }
        return ok().put("protocol", PROTOCOL).put("device", o)
                .put("customActions", new JSONArray(Registry.get().actionNames()))
                .put("customMatchers", new JSONArray(Registry.get().matcherNames()))
                .put("globalInterceptors", globals);
    }

    /** {@code {"cmd": "snapshot", "waitMs": 0}}：等界面安静后抓快照，返回 {@link SnapshotJson} 格式。 */
    private JSONObject snapshot(JSONObject request) throws DevtoolsException, JSONException {
        long waitMs = waitMs(request);
        ScreenSnapshot s = backend.call((session, waiter) -> session.snapshot(waiter, waitMs), 15_000 + waitMs);
        return ok().put("snapshot", SnapshotJson.write(s));
    }

    /** {@code {"cmd": "find", "seq": 5, "selectors": [{...}, ...]}}：在最近的快照上验证选择器。 */
    private JSONObject find(JSONObject request) throws DevtoolsException, JSONException {
        JSONArray array = request.optJSONArray("selectors");
        if (array == null || array.length() == 0) {
            throw new DevtoolsException(DevtoolsException.BAD_REQUEST, "\"selectors\" must be a non-empty array");
        }
        List<Selector> selectors = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String path = "selectors[" + i + "]";
            try {
                selectors.add(FlowParser.parseSelector(String.valueOf(array.opt(i))));
            } catch (FlowParseException e) {
                throw parseError(path, e);
            }
        }
        long seq = request.optLong("seq", -1);
        JSONObject result = backend.call((session, waiter) -> session.find(selectors, seq), 10_000);
        return merge(ok(), result);
    }

    /** {@code {"cmd": "act", "action": {...}, "waitMs": 0}}：执行一个动作，返回结果、页面是否变化和新快照。 */
    private JSONObject act(JSONObject request) throws DevtoolsException, JSONException {
        Object json = request.opt("action");
        if (json == null) {
            throw new DevtoolsException(DevtoolsException.BAD_REQUEST, "\"action\" is required");
        }
        Action action;
        try {
            action = FlowParser.parseAction(json.toString());
        } catch (FlowParseException e) {
            throw parseError("action", e);
        }
        long waitMs = waitMs(request);
        JSONObject result = backend.call((session, waiter) -> session.act(action, waiter, waitMs),
                60_000 + waitMs);
        return merge(ok(), result);
    }

    /**
     * {@code {"cmd": "validate", "flow": {...}}}：解析并校验流程（包括自定义名字是否已在宿主注册）。
     * 校验不通过不算请求失败，返回 {@code valid: false} 和全部问题。
     */
    private JSONObject validate(JSONObject request) throws DevtoolsException, JSONException {
        String ref = request.optString("flowRef", null);
        if (ref != null) {
            try {
                return ok().put("valid", true).put("flow", describe(FlowRefs.load(backend.classLoader(), ref)));
            } catch (FlowValidationException e) {
                JSONArray problems = new JSONArray();
                for (FlowValidationException.Problem p : e.getProblems()) {
                    problems.put(new JSONObject().put("path", FlowParser.pathOf(p)).put("message", p.getMessage()));
                }
                return ok().put("valid", false).put("problems", problems);
            }
        }
        String json = flowJson(request);
        try {
            Flow flow = FlowParser.parse(json);
            return ok().put("valid", true).put("flow", describe(flow));
        } catch (FlowParseException e) {
            JSONArray problems = new JSONArray();
            if (e.getCause() instanceof FlowValidationException) {
                for (FlowValidationException.Problem p : ((FlowValidationException) e.getCause()).getProblems()) {
                    problems.put(new JSONObject().put("path", FlowParser.pathOf(p)).put("message", p.getMessage()));
                }
            } else {
                problems.put(new JSONObject().put("path", e.getPath()).put("message", e.getReason()));
            }
            return ok().put("valid", false).put("path", e.getPath()).put("reason", e.getReason())
                    .put("problems", problems);
        }
    }

    /**
     * {@code {"cmd": "check", "flow": {...}, "snapshots": {"name": SNAPSHOT, ...}, "live": false, "waitMs": 0}}：
     * 用 {@link FlowCheck} 检查流程和页面样本的匹配关系。{@code live} 为 true 时再加一份名为 {@code live} 的实时快照；
     * 只有一份样本时给出每个不匹配格子的原因。
     */
    private JSONObject check(JSONObject request) throws DevtoolsException, JSONException {
        Flow flow = parseFlow(request);
        Map<String, ScreenSnapshot> samples = new LinkedHashMap<>();
        JSONObject snapshots = request.optJSONObject("snapshots");
        if (snapshots != null) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = snapshots.keys();
            while (it.hasNext()) {
                names.add(it.next());
            }
            // Maven 版 org.json 不保证键的顺序，排序后输出稳定
            Collections.sort(names);
            for (String name : names) {
                try {
                    samples.put(name, SnapshotJson.read(snapshots.opt(name)));
                } catch (DevtoolsException e) {
                    throw new DevtoolsException(DevtoolsException.BAD_REQUEST,
                            "snapshots." + name + ": " + e.getMessage());
                }
            }
        }
        boolean live = request.optBoolean("live", false);
        if (!live && samples.isEmpty()) {
            throw new DevtoolsException(DevtoolsException.BAD_REQUEST, "give \"snapshots\" or \"live\": true");
        }
        long waitMs = waitMs(request);
        JSONObject result = backend.call((session, waiter) -> {
            if (live) {
                samples.put("live", session.snapshot(waiter, waitMs));
            }
            // 自定义条件平时在引擎线程上执行，这里保持一致
            return FlowCheck.check(flow, Registry.get().globalInterceptors(), samples, samples.size() == 1);
        }, 20_000 + waitMs);
        return merge(ok(), result);
    }

    /** {@code {"cmd": "run", "flow": {...}}}：运行流程并流式推送轨迹。 */
    private void run(JSONObject request, LineSink out) throws IOException {
        Flow flow;
        try {
            flow = parseFlow(request);
        } catch (DevtoolsException e) {
            out.write(error(e.getCode(), e.getMessage(), e.getPath()).toString());
            return;
        }
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        RunHandle handle = backend.run(flow, queue::offer, queue::offer);
        try {
            while (true) {
                Object item;
                try {
                    item = queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    handle.cancel();
                    return;
                }
                if (item instanceof RunResult) {
                    out.write(ok().put("ev", "result").put("result", resultJson((RunResult) item)).toString());
                    return;
                }
                if (item instanceof TraceEvent) {
                    out.write(traceJson((TraceEvent) item).toString());
                } else {
                    out.write(new JSONObject().put("ev", "heartbeat").toString());
                }
            }
        } catch (IOException e) {
            // 客户端断开，不再有人看结果
            handle.cancel();
            throw e;
        } catch (JSONException e) {
            handle.cancel();
            out.write(error(DevtoolsException.INTERNAL, e.toString(), null).toString());
        }
    }

    /**
     * {@code {"cmd": "export", "flowRef": "..."}} 或 {@code {"flow": {...}}}：写成 JSON，并确认解析回来和原流程相等。
     * 有内联代码时返回 {@link DevtoolsException#NOT_EXPORTABLE}，消息里说明在哪个状态、哪个动作。
     */
    private JSONObject export(JSONObject request) throws DevtoolsException, JSONException {
        Flow flow = parseFlow(request);
        try {
            String json = FlowWriter.toJson(flow);
            return ok().put("json", json).put("flow", describe(flow));
        } catch (IllegalArgumentException e) {
            throw new DevtoolsException(DevtoolsException.NOT_EXPORTABLE, e.getMessage());
        }
    }

    // ---- helpers ----

    static JSONObject traceJson(TraceEvent e) throws JSONException {
        return new JSONObject().put("ev", "trace").put("t", e.getTimeMs()).put("type", e.getType().name())
                .put("priority", e.getPriority()).putOpt("state", e.getStateId()).put("msg", e.getMessage());
    }

    static JSONObject resultJson(RunResult r) throws JSONException {
        return new JSONObject().put("status", r.getStatus().name()).putOpt("flowId", r.getFlowId())
                .putOpt("runId", r.getRunId()).putOpt("endState", r.getEndStateId())
                .put("path", new JSONArray(r.getPath())).put("interceptors", new JSONObject(r.getInterceptorCounts()))
                .put("durationMs", r.getDurationMs()).putOpt("message", r.getMessage())
                .put("droppedTrace", r.getDroppedTraceEvents());
    }

    private static JSONObject describe(Flow flow) throws JSONException {
        JSONArray states = new JSONArray();
        for (State state : flow.getStates()) {
            states.put(new JSONObject().put("id", state.getId()).put("next", new JSONArray(state.getNext()))
                    .put("end", state.isTerminal() ? state.getEnd().name().toLowerCase(Locale.ROOT) : JSONObject.NULL)
                    .put("actions", state.getActions().size()));
        }
        Set<String> own = new HashSet<>();
        JSONArray interceptors = new JSONArray();
        for (Interceptor interceptor : flow.getInterceptors()) {
            own.add(interceptor.getId());
            interceptors.put(interceptor.getId());
        }
        JSONArray globals = new JSONArray();
        for (Interceptor interceptor : flow.effectiveInterceptors(Registry.get().globalInterceptors())) {
            if (!own.contains(interceptor.getId())) {
                globals.put(interceptor.getId());
            }
        }
        return new JSONObject().put("id", flow.getId()).put("start", flow.getStart()).put("states", states)
                .put("interceptors", interceptors).put("globalInterceptors", globals);
    }

    private Flow parseFlow(JSONObject request) throws DevtoolsException {
        String ref = request.optString("flowRef", null);
        if (ref != null) {
            try {
                return FlowRefs.load(backend.classLoader(), ref);
            } catch (FlowValidationException e) {
                throw new DevtoolsException(DevtoolsException.INVALID_FLOW, e.getMessage());
            }
        }
        try {
            return FlowParser.parse(flowJson(request));
        } catch (FlowParseException e) {
            throw parseError("flow", e);
        }
    }

    /** {@code flow} 可以是 JSON 对象，也可以是 JSON 字符串。 */
    private static String flowJson(JSONObject request) throws DevtoolsException {
        Object flow = request.opt("flow");
        if (flow instanceof JSONObject) {
            return flow.toString();
        }
        if (flow instanceof String) {
            return (String) flow;
        }
        throw new DevtoolsException(DevtoolsException.BAD_REQUEST,
                "give \"flow\" (JSON object or string) or \"flowRef\" (\"com.foo.Flows#method\")");
    }

    private static DevtoolsException parseError(String prefix, FlowParseException e) {
        String path = e.getPath().isEmpty() ? prefix : prefix + "." + e.getPath();
        return new DevtoolsException(DevtoolsException.PARSE_ERROR, path + ": " + e.getReason(), path);
    }

    private static long waitMs(JSONObject request) {
        return Math.max(0, Math.min(MAX_WAIT_MS, request.optLong("waitMs", 0)));
    }

    private static JSONObject merge(JSONObject target, JSONObject source) throws JSONException {
        Iterator<String> it = source.keys();
        while (it.hasNext()) {
            String key = it.next();
            target.put(key, source.opt(key));
        }
        return target;
    }

    private static JSONObject ok() throws JSONException {
        return new JSONObject().put("ok", true);
    }

    private static JSONObject error(String code, String message, String path) {
        try {
            return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code)
                    .put("message", message != null ? message : code).putOpt("path", path));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}

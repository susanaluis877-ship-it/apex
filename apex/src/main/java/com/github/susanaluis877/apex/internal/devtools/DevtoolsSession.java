package com.github.susanaluis877.apex.internal.devtools;

import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.internal.engine.ActionExecutor;
import com.github.susanaluis877.apex.internal.engine.ActionResult;
import com.github.susanaluis877.apex.internal.engine.ActionRunner;
import com.github.susanaluis877.apex.internal.engine.Clock;
import com.github.susanaluis877.apex.internal.engine.EngineConfig;
import com.github.susanaluis877.apex.internal.engine.Fingerprint;
import com.github.susanaluis877.apex.internal.engine.ScreenSource;
import com.github.susanaluis877.apex.internal.engine.Settler;
import com.github.susanaluis877.apex.internal.engine.Waiter;
import com.github.susanaluis877.apex.match.Selector;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * devtools 的屏幕操作：抓快照、在快照上验证选择器、执行单个动作。只能在引擎线程上使用。
 *
 * <p>整个会话用同一个 {@link ScreenSource}，所以快照序号单调递增；最近一次快照会被保留，供 {@link #find} 使用。
 * 动作总是基于执行前现抓的快照，因为执行器只认识 source 最新一次返回的节点。
 */
public final class DevtoolsSession {

    /** {@link #find} 每个选择器最多返回这么多个命中节点。 */
    static final int MAX_HANDLES = 20;
    /** 页面切换动画期间没有无障碍事件，等到相隔这么久的两次快照一致才认为稳定。 */
    static final long STABLE_INTERVAL_MS = 200;
    static final int STABLE_TRIES = 3;

    private final ScreenSource source;
    private final ActionExecutor executor;
    private final Clock clock;
    private final EngineConfig config;
    private ScreenSnapshot last;

    public DevtoolsSession(ScreenSource source, ActionExecutor executor, Clock clock, EngineConfig config) {
        this.source = source;
        this.executor = executor;
        this.clock = clock;
        this.config = config;
    }

    /**
     * 先等 {@code waitMs}（页面在加载时用），再等界面安静，然后抓快照并保留。
     */
    public ScreenSnapshot snapshot(Waiter waiter, long waitMs) throws DevtoolsException {
        waiter.clearEvents();
        if (waitMs > 0) {
            waiter.sleep(waitMs);
        }
        Settler.settle(waiter, clock, config);
        return captureStable(waiter);
    }

    /**
     * 在保留的快照上验证一批选择器。
     *
     * @param seq 调用方看到的快照序号；不是保留的那份时报 {@link DevtoolsException#STALE_SNAPSHOT}，小于 0 表示不检查
     */
    public JSONObject find(List<Selector> selectors, long seq) throws DevtoolsException, JSONException {
        if (last == null) {
            throw new DevtoolsException(DevtoolsException.STALE_SNAPSHOT, "no snapshot yet; take one first");
        }
        if (seq >= 0 && seq != last.getSeq()) {
            throw new DevtoolsException(DevtoolsException.STALE_SNAPSHOT, "snapshot " + seq
                    + " is no longer the latest (latest is " + last.getSeq() + "); take a new one");
        }
        JSONArray results = new JSONArray();
        for (Selector selector : selectors) {
            List<UiNode> nodes = selector.findAll(last);
            JSONArray handles = new JSONArray();
            for (int i = 0; i < nodes.size() && i < MAX_HANDLES; i++) {
                handles.put(nodes.get(i).getHandle());
            }
            JSONObject r = new JSONObject().put("selector", selector.toString()).put("count", nodes.size())
                    .put("handles", handles);
            if (nodes.isEmpty()) {
                r.putOpt("explain", selector.explainMismatch(last));
            }
            results.put(r);
        }
        return new JSONObject().put("seq", last.getSeq()).put("results", results);
    }

    /**
     * 现抓快照、执行动作、等界面安静、再抓快照。返回执行结果、页面是否变化和新快照。
     *
     * @param waitMs 动作执行后、等界面安静前先等的时间
     */
    public JSONObject act(Action action, Waiter waiter, long waitMs) throws DevtoolsException, JSONException {
        waiter.clearEvents();
        ScreenSnapshot previous = last;
        ScreenSnapshot before = capture();
        // 上次 snapshot/act 之后页面已经变了（例如中间跑过流程），客户端缓存的页面不能当“动作前”
        boolean stale = previous == null || Fingerprint.ofAll(previous) != Fingerprint.ofAll(before);
        long start = clock.now();
        List<TraceEvent> nested = new ArrayList<>();
        ActionResult r = ActionRunner.runOnce(action, before, source, executor, waiter, clock, nested);
        long took = clock.now() - start;
        if (waitMs > 0 && !waiter.isCancelled()) {
            waiter.sleep(waitMs);
        }
        Settler.settle(waiter, clock, config);
        ScreenSnapshot after = captureStable(waiter);
        JSONArray steps = new JSONArray();
        for (TraceEvent e : nested) {
            steps.put(e.getMessage().trim());
        }
        JSONObject result = new JSONObject();
        if (stale) {
            result.put("beforeSnapshot", SnapshotJson.write(before));
        }
        return result
                .put("action", action.toString())
                .put("status", r.getStatus().name())
                .putOpt("message", r.getMessage())
                .put("tookMs", took)
                .put("nested", steps)
                .put("before", before.getSeq())
                .put("changed", Fingerprint.ofAll(before) != Fingerprint.ofAll(after))
                .put("snapshot", SnapshotJson.write(after));
    }

    /** 最近一次快照，没有时为 null。 */
    public ScreenSnapshot last() {
        return last;
    }

    /**
     * 抓快照，直到连续两次一致（含节点位置）为止，最多再抓 {@link #STABLE_TRIES} 次。
     * 页面滑入动画期间节点的位置在变，坐标和截图都对不上。
     */
    private ScreenSnapshot captureStable(Waiter waiter) throws DevtoolsException {
        ScreenSnapshot s = capture();
        for (int i = 0; i < STABLE_TRIES && !waiter.isCancelled(); i++) {
            waiter.sleep(STABLE_INTERVAL_MS);
            ScreenSnapshot next = capture();
            if (Fingerprint.ofAll(next) == Fingerprint.ofAll(s)) {
                return next;
            }
            s = next;
        }
        return s;
    }

    private ScreenSnapshot capture() throws DevtoolsException {
        ScreenSnapshot s = source.snapshot();
        if (s == null) {
            throw new DevtoolsException(DevtoolsException.SERVICE_UNAVAILABLE,
                    "accessibility service is not connected");
        }
        last = s;
        return s;
    }
}

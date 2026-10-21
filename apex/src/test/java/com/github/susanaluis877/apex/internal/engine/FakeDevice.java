package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 测试用的假设备，同时充当屏幕、执行器、时钟和等待器。
 *
 * <ul>
 *   <li>时间只在引擎等待或休眠时推进，测试瞬间跑完且完全确定。</li>
 *   <li>{@link #at} 安排在某个时间点发生的变化；{@link #onClick} 定义点击某个 id 的效果。</li>
 *   <li>{@link #log} 按顺序记录所有设备操作，例如 {@code click:start}、{@code tap:540,1200}、{@code back}。</li>
 * </ul>
 */
public final class FakeDevice implements ScreenSource, ActionExecutor, Clock, Waiter {

    public final List<String> log = new ArrayList<>();
    public final List<Long> logTimes = new ArrayList<>();

    private long now = 1_000;
    private Supplier<ScreenSnapshot> screen;
    private boolean pendingEvent;
    private boolean cancelled;
    private boolean serviceDown;
    private final TreeMap<Long, List<Runnable>> scheduled = new TreeMap<>();
    private final Map<String, Runnable> clickEffects = new HashMap<>();
    private final Map<Action.GlobalAction, Runnable> globalEffects = new HashMap<>();
    private final Map<String, ActionResult> clickResults = new HashMap<>();
    private ActionResult launchResult = ActionResult.OK;
    private final Map<Action.Direction, ActionResult> scrollResults = new HashMap<>();
    private Runnable scrollEffect;

    // ---- 测试脚本 ----

    public void show(ScreenSnapshot snapshot) {
        show(() -> snapshot);
    }

    public void show(Supplier<ScreenSnapshot> supplier) {
        screen = supplier;
        pendingEvent = true;
    }

    /** 在相对当前时间 {@code delayMs} 之后执行。 */
    public void after(long delayMs, Runnable change) {
        at(now + delayMs, change);
    }

    public void at(long time, Runnable change) {
        scheduled.computeIfAbsent(time, t -> new ArrayList<>()).add(change);
    }

    public void onClick(String id, Runnable effect) {
        clickEffects.put(id, effect);
    }

    public void onGlobal(Action.GlobalAction kind, Runnable effect) {
        globalEffects.put(kind, effect);
    }

    public void clickResult(String id, ActionResult result) {
        clickResults.put(id, result);
    }

    /** 某个方向的节点滚动返回 {@code result}（默认成功）。 */
    public void scrollResult(Action.Direction direction, ActionResult result) {
        scrollResults.put(direction, result);
    }

    /** 每次滚动成功（节点动作或滑动手势）后执行。 */
    public void onScroll(Runnable effect) {
        scrollEffect = effect;
    }

    public void launchResult(ActionResult result) {
        launchResult = result;
    }

    public void serviceDown(boolean down) {
        serviceDown = down;
    }

    public void cancel() {
        cancelled = true;
    }

    // ---- ScreenSource ----

    @Override
    public ScreenSnapshot snapshot() {
        return serviceDown || screen == null ? null : screen.get();
    }

    // ---- ActionExecutor ----

    @Override
    public ActionResult clickNode(UiNode node, boolean longClick) {
        String id = shortId(node);
        record((longClick ? "longClick:" : "click:") + id);
        ActionResult result = clickResults.getOrDefault(id, ActionResult.OK);
        if (result.isOk()) {
            Runnable effect = clickEffects.get(id);
            if (effect != null) {
                effect.run();
            }
        }
        return result;
    }

    @Override
    public ActionResult scrollNode(UiNode node, Action.Direction direction) {
        record("scroll:" + shortId(node) + ":" + direction);
        ActionResult result = scrollResults.getOrDefault(direction, ActionResult.OK);
        if (result.isOk() && scrollEffect != null) {
            scrollEffect.run();
        }
        return result;
    }

    @Override
    public ActionResult setProgress(UiNode node, float value) {
        record("setProgress:" + shortId(node) + "=" + value);
        return ActionResult.OK;
    }

    @Override
    public ActionResult setText(UiNode node, String text) {
        record("setText:" + shortId(node) + "=" + text);
        return ActionResult.OK;
    }

    @Override
    public ActionResult gesture(int x1, int y1, int x2, int y2, long durationMs) {
        if (x1 == x2 && y1 == y2) {
            record("tap:" + x1 + "," + y1);
            ScreenSnapshot s = snapshot();
            if (s != null) {
                for (UiNode n : s.nodes()) {
                    String id = shortId(n);
                    if (clickEffects.containsKey(id) && n.getBounds().centerX() == x1
                            && n.getBounds().centerY() == y1) {
                        clickEffects.get(id).run();
                        break;
                    }
                }
            }
        } else {
            record("swipe:" + x1 + "," + y1 + "->" + x2 + "," + y2);
            if (scrollEffect != null) {
                scrollEffect.run();
            }
        }
        return ActionResult.OK;
    }

    @Override
    public ActionResult global(Action.GlobalAction kind) {
        record(kind.name().toLowerCase());
        Runnable effect = globalEffects.get(kind);
        if (effect != null) {
            effect.run();
        }
        return ActionResult.OK;
    }

    @Override
    public ActionResult launchApp(String packageName) {
        record("launch:" + packageName);
        return launchResult;
    }

    // ---- Clock / Waiter ----

    @Override
    public long now() {
        return now;
    }

    public long elapsedSince(long start) {
        return now - start;
    }

    @Override
    public boolean awaitEvent(long timeoutMs) {
        long end = now + timeoutMs;
        while (true) {
            if (cancelled) {
                return false;
            }
            if (pendingEvent) {
                pendingEvent = false;
                return true;
            }
            Map.Entry<Long, List<Runnable>> next = scheduled.firstEntry();
            if (next == null || next.getKey() > end) {
                now = end;
                return false;
            }
            runScheduled(next);
        }
    }

    @Override
    public void sleep(long ms) {
        long end = now + ms;
        while (!cancelled) {
            Map.Entry<Long, List<Runnable>> next = scheduled.firstEntry();
            if (next == null || next.getKey() > end) {
                now = end;
                return;
            }
            runScheduled(next);
        }
    }

    @Override
    public void clearEvents() {
        pendingEvent = false;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    private void runScheduled(Map.Entry<Long, List<Runnable>> entry) {
        scheduled.remove(entry.getKey());
        now = Math.max(now, entry.getKey());
        for (Runnable r : entry.getValue()) {
            r.run();
        }
    }

    private void record(String op) {
        log.add(op);
        logTimes.add(now);
    }

    private static String shortId(UiNode node) {
        String id = node.getViewId();
        if (id != null) {
            int i = id.indexOf(":id/");
            return i >= 0 ? id.substring(i + 4) : id;
        }
        return node.getText();
    }
}

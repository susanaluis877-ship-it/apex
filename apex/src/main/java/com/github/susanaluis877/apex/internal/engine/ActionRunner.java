package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.ApexLogger;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.action.ActionContext;
import com.github.susanaluis877.apex.action.ActionFunction;
import com.github.susanaluis877.apex.action.Actions;
import com.github.susanaluis877.apex.action.Coord;
import com.github.susanaluis877.apex.action.CustomAction;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.match.Selector;
import com.github.susanaluis877.apex.node.Bounds;
import com.github.susanaluis877.apex.node.Range;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import java.util.List;

/**
 * 把 {@link Action} 描述翻译成设备操作：在快照里找目标、找可点击的祖先、必要时退回坐标手势。
 *
 * <p>成功的结果都带上实际做了什么（哪个节点、哪种方式、坐标），供运行轨迹使用。
 */
public final class ActionRunner {

    /** scrollTo 每次滚动后等这么久再抓快照，让滚动动画结束。 */
    static final long SCROLL_SETTLE_MS = 350;

    private final ScreenSource source;
    private final ActionExecutor executor;
    private final Waiter waiter;
    private final Tracer tracer;

    ActionRunner(ScreenSource source, ActionExecutor executor, Waiter waiter, Tracer tracer) {
        this.source = source;
        this.executor = executor;
        this.waiter = waiter;
        this.tracer = tracer;
    }

    /**
     * 在流程之外执行单个动作，供 devtools 使用。{@code screen} 必须是 {@code source} 最新一次返回的快照。
     *
     * @param trace 自定义动作里通过 {@link ActionContext} 做的操作会追加到这里，可以为 null
     */
    public static ActionResult runOnce(Action action, ScreenSnapshot screen, ScreenSource source,
                                       ActionExecutor executor, Waiter waiter, Clock clock, List<TraceEvent> trace) {
        Tracer tracer = new Tracer("devtools", clock);
        tracer.start(clock.now());
        ActionResult r = new ActionRunner(source, executor, waiter, tracer).run(action, screen, null);
        if (trace != null) {
            trace.addAll(tracer.events());
        }
        return r;
    }

    ActionResult run(Action action, ScreenSnapshot screen, String stateId) {
        if (action.getDelayMs() > 0) {
            waiter.sleep(action.getDelayMs());
            if (waiter.isCancelled()) {
                return ActionResult.cancelled();
            }
            screen = source.snapshot();
            if (screen == null) {
                return ActionResult.soft("service unavailable after delay");
            }
        }
        if (action instanceof Action.Click) {
            Action.Click click = (Action.Click) action;
            UiNode node = click.getTarget().findFirst(screen);
            if (node == null) {
                return ActionResult.soft("target not found: " + click.getTarget().explainMismatch(screen));
            }
            return click(node, click.getMode(), click.isLongClick(), screen);
        }
        if (action instanceof Action.Tap) {
            Action.Tap tap = (Action.Tap) action;
            int x = clampX(tap.getPoint(), screen);
            int y = clampY(tap.getPoint(), screen);
            return detail(executor.gesture(x, y, x, y, tap.getDurationMs()),
                    "tap (" + x + "," + y + ") " + tap.getDurationMs() + "ms");
        }
        if (action instanceof Action.Swipe) {
            Action.Swipe swipe = (Action.Swipe) action;
            int x1 = clampX(swipe.getFrom(), screen);
            int y1 = clampY(swipe.getFrom(), screen);
            int x2 = clampX(swipe.getTo(), screen);
            int y2 = clampY(swipe.getTo(), screen);
            return detail(executor.gesture(x1, y1, x2, y2, swipe.getDurationMs()),
                    "swipe (" + x1 + "," + y1 + ")->(" + x2 + "," + y2 + ") " + swipe.getDurationMs() + "ms");
        }
        if (action instanceof Action.Scroll) {
            return scroll((Action.Scroll) action, screen);
        }
        if (action instanceof Action.ScrollTo) {
            return scrollTo((Action.ScrollTo) action, screen);
        }
        if (action instanceof Action.SetProgress) {
            return setProgress((Action.SetProgress) action, screen);
        }
        if (action instanceof Action.SetText) {
            Action.SetText setText = (Action.SetText) action;
            UiNode node = setText.getTarget().findFirst(screen);
            if (node == null) {
                return ActionResult.soft("target not found: " + setText.getTarget().explainMismatch(screen));
            }
            if (!node.isEditable()) {
                return ActionResult.hard("target is not editable: " + node);
            }
            return detail(executor.setText(node, setText.getText()), "ACTION_SET_TEXT on " + node);
        }
        if (action instanceof Action.Global) {
            Action.GlobalAction kind = ((Action.Global) action).getKind();
            return detail(executor.global(kind), "GLOBAL_ACTION_" + kind);
        }
        if (action instanceof Action.LaunchApp) {
            String pkg = ((Action.LaunchApp) action).getPackageName();
            return detail(executor.launchApp(pkg), "launched " + pkg);
        }
        if (action instanceof Action.Sleep) {
            long ms = ((Action.Sleep) action).getMs();
            waiter.sleep(ms);
            return waiter.isCancelled() ? ActionResult.cancelled() : ActionResult.ok("slept " + ms + "ms");
        }
        if (action instanceof Action.Custom) {
            Action.Custom custom = (Action.Custom) action;
            CustomAction registered = Registry.get().action(custom.getName());
            if (registered == null) {
                return ActionResult.error("custom action '" + custom.getName() + "' is not registered");
            }
            return invoke("custom '" + custom.getName() + "'", screen, stateId,
                    ctx -> registered.perform(ctx, custom.getArgs()));
        }
        if (action instanceof Action.Inline) {
            return invoke("run(<inline>)", screen, stateId, ((Action.Inline) action).getFunction());
        }
        return ActionResult.error("unsupported action: " + action);
    }

    ActionResult click(UiNode node, Action.ClickMode mode, boolean longClick, ScreenSnapshot screen) {
        String nodeAction = longClick ? "ACTION_LONG_CLICK" : "ACTION_CLICK";
        String nodeFailure = null;
        if (mode != Action.ClickMode.GESTURE) {
            UiNode clickable = clickableSelfOrAncestor(node, longClick);
            if (clickable != null && clickable != node && mode == Action.ClickMode.AUTO
                    && isContainerOf(clickable, node, screen) && onScreen(node.getBounds(), screen)) {
                // 可点击的祖先是整个列表、月视图或全屏遮罩：对它 ACTION_CLICK 点不到目标，而且常常返回成功却没反应。
                // 改按目标中心坐标点（便签列表项、日历日期格都是这样）
                nodeFailure = "skipped " + nodeAction + " on ancestor " + clickable + ": it is a container, not the target";
            } else if (clickable != null) {
                ActionResult r = executor.clickNode(clickable, longClick);
                String on = clickable == node ? " on " + node : " on ancestor " + clickable + " of " + node;
                if (r.isOk()) {
                    return ActionResult.ok(nodeAction + on);
                }
                if (mode == Action.ClickMode.NODE) {
                    return r;
                }
                nodeFailure = nodeAction + on + " failed: " + r.getMessage();
            } else if (mode == Action.ClickMode.NODE) {
                return ActionResult.soft("no " + (longClick ? "long-" : "") + "clickable node for " + node);
            } else {
                nodeFailure = "no " + (longClick ? "long-" : "") + "clickable node or ancestor";
            }
        }
        Bounds b = node.getBounds();
        if (b.isEmpty()) {
            return ActionResult.soft("target has empty bounds: " + node
                    + (nodeFailure != null ? "; " + nodeFailure : ""));
        }
        if (!onScreen(b, screen)) {
            return ActionResult.soft(offScreen(node) + (nodeFailure != null ? "; " + nodeFailure : ""));
        }
        int x = clamp(b.centerX(), screen.getScreenWidth());
        int y = clamp(b.centerY(), screen.getScreenHeight());
        long duration = longClick ? Actions.DEFAULT_LONG_PRESS_MS : Actions.DEFAULT_TAP_MS;
        String detail = (longClick ? "long-press" : "tap") + " (" + x + "," + y + ") " + duration + "ms on " + node
                + (nodeFailure != null ? " after " + nodeFailure : "");
        return detail(executor.gesture(x, y, x, y, duration), detail);
    }

    private ActionResult scroll(Action.Scroll scroll, ScreenSnapshot screen) {
        UiNode node = container(scroll.getTarget(), screen);
        if (node == null) {
            return ActionResult.soft(noContainer(scroll.getTarget(), screen));
        }
        return scrollNode(node, scroll.getDirection(), screen);
    }

    /**
     * 选择器命中的节点或它最近的可滚动祖先；选择器为 null 时取屏幕上第一个可见的可滚动节点，但不取左右翻页的容器。
     *
     * <p>左右翻页的 ViewPager 一滚就翻到了另一个标签页（ColorOS 便签的「笔记 / 待办」），要翻页时把它写成 container。
     * 同样的原因，往上找时遇到不能滚的列表就停下：列表内容一屏放得下时它不可滚动，再往外找常会找到 ViewPager。
     */
    private static UiNode container(Selector selector, ScreenSnapshot screen) {
        if (selector == null) {
            for (UiNode n : screen.nodes()) {
                if (n.isVisible() && n.isScrollable() && !isPager(n)) {
                    return n;
                }
            }
            return null;
        }
        UiNode node = selector.findFirst(screen);
        while (node != null && !node.isScrollable()) {
            if (isListClass(node.getClassName())) {
                return null;
            }
            node = node.getParent();
        }
        return node;
    }

    /** {@link #container} 找不到时的原因。 */
    private static String noContainer(Selector selector, ScreenSnapshot screen) {
        if (selector == null) {
            for (UiNode n : screen.nodes()) {
                if (n.isVisible() && n.isScrollable()) {
                    return "no scrollable list on screen; skipped " + n + ", which flips between pages"
                            + " (give it as the container to scroll it)";
                }
            }
            return "no scrollable node on screen";
        }
        for (UiNode n = selector.findFirst(screen); n != null; n = n.getParent()) {
            if (!n.isScrollable() && isListClass(n.getClassName())) {
                return n + " is not scrollable now (its content fits on the screen); not scrolling its ancestors";
            }
        }
        return "no scrollable container for " + selector + ": " + selector.explainMismatch(screen);
    }

    /**
     * 滚动节点。很多列表（例如 ColorOS 的 RecyclerView）只支持 forward/backward 不支持 up/down，
     * 所以方向动作失败时先换成对应的 forward/backward，还不行就在节点范围内滑动。
     */
    private ActionResult scrollNode(UiNode node, Action.Direction direction, ScreenSnapshot screen) {
        ActionResult r = executor.scrollNode(node, direction);
        if (r.isOk()) {
            return ActionResult.ok("ACTION_SCROLL_" + direction + " on " + node);
        }
        if (r.getStatus() != ActionResult.Status.SOFT_FAIL) {
            return r;
        }
        String failures = "ACTION_SCROLL_" + direction + " failed (" + r.getMessage() + ")";
        if (direction != Action.Direction.FORWARD && direction != Action.Direction.BACKWARD) {
            Action.Direction generic = direction == Action.Direction.DOWN || direction == Action.Direction.RIGHT
                    ? Action.Direction.FORWARD : Action.Direction.BACKWARD;
            ActionResult r2 = executor.scrollNode(node, generic);
            if (r2.isOk()) {
                return ActionResult.ok("ACTION_SCROLL_" + generic + " on " + node + " after " + failures);
            }
            if (r2.getStatus() != ActionResult.Status.SOFT_FAIL) {
                return r2;
            }
            failures += ", ACTION_SCROLL_" + generic + " failed (" + r2.getMessage() + ")";
        }
        // 节点自己报告了能往哪边滚时，不能往这边滚就是已经到头了，不必再滑
        if ((node.canScrollForward() || node.canScrollBackward()) && !canScroll(node, direction)) {
            return ActionResult.soft("already at the " + (towardsEnd(direction) ? "end" : "start") + " of " + node);
        }
        Bounds b = node.getBounds();
        if (b.isEmpty()) {
            return ActionResult.soft(failures + "; node has empty bounds, cannot swipe");
        }
        if (!onScreen(b, screen)) {
            return ActionResult.soft(offScreen(node) + "; " + failures);
        }
        boolean vertical = direction == Action.Direction.UP || direction == Action.Direction.DOWN
                || (direction == Action.Direction.FORWARD || direction == Action.Direction.BACKWARD)
                && b.height() >= b.width();
        boolean towardsEnd = towardsEnd(direction);
        int x1;
        int y1;
        int x2;
        int y2;
        if (vertical) {
            // 看后面的内容：手指从下往上滑
            int near = b.getTop() + b.height() * 3 / 4;
            int far = b.getTop() + b.height() / 4;
            x1 = b.centerX();
            x2 = b.centerX();
            y1 = towardsEnd ? near : far;
            y2 = towardsEnd ? far : near;
        } else {
            int near = b.getLeft() + b.width() * 3 / 4;
            int far = b.getLeft() + b.width() / 4;
            y1 = b.centerY();
            y2 = b.centerY();
            x1 = towardsEnd ? near : far;
            x2 = towardsEnd ? far : near;
        }
        x1 = clamp(x1, screen.getScreenWidth());
        x2 = clamp(x2, screen.getScreenWidth());
        y1 = clamp(y1, screen.getScreenHeight());
        y2 = clamp(y2, screen.getScreenHeight());
        return detail(executor.gesture(x1, y1, x2, y2, Actions.DEFAULT_SWIPE_MS), "swipe (" + x1 + "," + y1
                + ")->(" + x2 + "," + y2 + ") in " + node + " after " + failures);
    }

    private ActionResult scrollTo(Action.ScrollTo action, ScreenSnapshot screen) {
        ScreenSnapshot s = screen;
        // 滚轮选择器的容器自己带着当前值（例如「09小时选择器项目」），失败时列出见过的值，一眼看出格式对不对（补零等）
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int swipes = 0; ; swipes++) {
            UiNode found = action.getTarget().findFirst(s);
            if (found != null) {
                return ActionResult.ok("found " + found + (swipes == 0 ? " without scrolling"
                        : " after " + swipes + " scroll(s)"));
            }
            UiNode node = container(action.getContainer(), s);
            if (node != null && seen.size() < 30) {
                String value = node.getText() != null ? node.getText() : node.getDesc();
                if (value != null) {
                    seen.add(value.length() > 24 ? value.substring(0, 24) + "…" : value);
                }
            }
            if (swipes >= action.getMaxSwipes()) {
                return ActionResult.soft("target not found after " + swipes + " scroll(s): "
                        + action.getTarget().explainMismatch(s) + seenValues(seen));
            }
            if (node == null) {
                return ActionResult.soft("target not found: " + action.getTarget().explainMismatch(s) + "; "
                        + noContainer(action.getContainer(), s));
            }
            if ((node.canScrollForward() || node.canScrollBackward()) && !canScroll(node, action.getDirection())) {
                return ActionResult.soft("reached the " + (towardsEnd(action.getDirection()) ? "end" : "start")
                        + " of " + node + " after " + swipes + " scroll(s) without finding " + action.getTarget()
                        + seenValues(seen));
            }
            long before = Fingerprint.content(s);
            ActionResult r = scrollNode(node, action.getDirection(), s);
            if (!r.isOk()) {
                return r;
            }
            waiter.sleep(SCROLL_SETTLE_MS);
            if (waiter.isCancelled()) {
                return ActionResult.cancelled();
            }
            s = source.snapshot();
            if (s == null) {
                return ActionResult.soft("service unavailable while scrolling");
            }
            if (Fingerprint.content(s) == before) {
                return ActionResult.soft("reached the end of " + node + " after " + (swipes + 1)
                        + " scroll(s) without finding " + action.getTarget() + seenValues(seen));
            }
        }
    }

    private static String seenValues(java.util.Set<String> seen) {
        return seen.size() < 2 ? "" : "; container showed " + String.join(", ", seen);
    }

    private ActionResult setProgress(Action.SetProgress action, ScreenSnapshot screen) {
        UiNode node = action.getTarget().findFirst(screen);
        if (node == null) {
            return ActionResult.soft("target not found: " + action.getTarget().explainMismatch(screen));
        }
        UiNode slider = sliderSelfOrDescendant(node);
        if (slider == null) {
            return ActionResult.hard("target is not a slider and contains none: " + node);
        }
        Range range = slider.getRange();
        if (range == null) {
            if (action.getValue() == null) {
                return ActionResult.hard("slider reports no range, so a ratio cannot be converted: " + slider);
            }
            float value = action.getValue().floatValue();
            return detail(executor.setProgress(slider, value), "ACTION_SET_PROGRESS " + value + " on " + slider);
        }
        float min = range.getMin();
        float max = range.getMax();
        float value = action.getValue() != null
                ? (float) Math.max(min, Math.min(max, action.getValue()))
                : (float) (min + action.getRatio() * (max - min));
        if (range.getType() == Range.Type.INT) {
            value = Math.round(value);
        }
        // SeekBar 在值不变时让 ACTION_SET_PROGRESS 返回 false，所以已经到位就直接算成功
        if (Math.abs(range.getCurrent() - value) < 0.5f || range.getType() != Range.Type.INT
                && Math.abs(range.getCurrent() - value) <= (max - min) / 1000f) {
            return ActionResult.ok("already at " + Range.format(value) + ": " + slider);
        }
        String failure = null;
        if (slider.canSetProgress()) {
            ActionResult r = executor.setProgress(slider, value);
            if (r.isOk()) {
                return ActionResult.ok("ACTION_SET_PROGRESS " + Range.format(value) + " on " + slider
                        + " (was " + range + ")");
            }
            if (r.getStatus() != ActionResult.Status.SOFT_FAIL) {
                return r;
            }
            failure = "ACTION_SET_PROGRESS failed (" + r.getMessage() + ")";
        }
        // 不支持直接设值时按比例位置点击：横向滑块点 x，纵向滑块点 y
        Bounds b = slider.getBounds();
        if (b.isEmpty()) {
            return ActionResult.soft("slider has empty bounds: " + slider + (failure != null ? "; " + failure : ""));
        }
        if (!onScreen(b, screen)) {
            return ActionResult.soft(offScreen(slider) + (failure != null ? "; " + failure : ""));
        }
        float ratio = max > min ? (value - min) / (max - min) : 0f;
        boolean horizontal = b.width() >= b.height();
        int x = horizontal ? b.getLeft() + Math.round(ratio * (b.width() - 1)) : b.centerX();
        int y = horizontal ? b.centerY() : b.getBottom() - 1 - Math.round(ratio * (b.height() - 1));
        x = clamp(x, screen.getScreenWidth());
        y = clamp(y, screen.getScreenHeight());
        return detail(executor.gesture(x, y, x, y, Actions.DEFAULT_TAP_MS), "tap (" + x + "," + y + ") at "
                + Math.round(ratio * 100) + "% of " + slider + (failure != null ? " after " + failure : ""));
    }

    private static boolean towardsEnd(Action.Direction direction) {
        return direction == Action.Direction.DOWN || direction == Action.Direction.RIGHT
                || direction == Action.Direction.FORWARD;
    }

    private static boolean canScroll(UiNode node, Action.Direction direction) {
        return towardsEnd(direction) ? node.canScrollForward() : node.canScrollBackward();
    }

    /** 节点中心在屏幕内。页面切换动画期间节点可能还在屏幕外，这时按坐标操作会点到别处。 */
    private static boolean onScreen(Bounds b, ScreenSnapshot screen) {
        int w = screen.getScreenWidth();
        int h = screen.getScreenHeight();
        return (w <= 0 || b.centerX() >= 0 && b.centerX() < w) && (h <= 0 || b.centerY() >= 0 && b.centerY() < h);
    }

    private static String offScreen(UiNode node) {
        return "target is outside the screen (page still animating?), not tapping by coordinates: " + node;
    }

    private static UiNode sliderSelfOrDescendant(UiNode node) {
        if (node.getRange() != null || node.canSetProgress()) {
            return node;
        }
        for (UiNode child : node.getChildren()) {
            UiNode found = sliderSelfOrDescendant(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 执行宿主代码（注册的自定义动作或内联函数）：true 成功，false 软失败，异常结束流程。 */
    private ActionResult invoke(String label, ScreenSnapshot screen, String stateId, ActionFunction function) {
        Context context = new Context(screen, stateId, label);
        try {
            if (function.perform(context)) {
                return ActionResult.ok(label + " returned true");
            }
            return waiter.isCancelled() ? ActionResult.cancelled() : ActionResult.soft(label + " returned false");
        } catch (Exception e) {
            return ActionResult.error(label + " threw " + e);
        }
    }

    /** 执行器成功时换成带细节的结果，失败时原样返回。 */
    private static ActionResult detail(ActionResult r, String detail) {
        return r.isOk() ? ActionResult.ok(detail) : r;
    }

    private static UiNode clickableSelfOrAncestor(UiNode node, boolean longClick) {
        for (UiNode n = node; n != null; n = n.getParent()) {
            if (longClick ? n.isLongClickable() : n.isClickable()) {
                return n;
            }
        }
        return null;
    }

    /**
     * 祖先是容器而不是目标的一部分：列表 / 滚动容器，或者占了屏幕的四分之一以上、面积是目标的 8 倍以上
     * （月视图、全屏遮罩）。列表里一行的可点击容器不算，对它 ACTION_CLICK 就是点这一行。
     */
    private static boolean isContainerOf(UiNode ancestor, UiNode target, ScreenSnapshot screen) {
        if (ancestor.isScrollable() || isListClass(ancestor.getClassName())) {
            return true;
        }
        long a = area(ancestor.getBounds());
        long t = area(target.getBounds());
        long s = (long) screen.getScreenWidth() * screen.getScreenHeight();
        return t > 0 && s > 0 && a * 4 >= s && a >= t * 8;
    }

    private static long area(Bounds b) {
        return b.isEmpty() ? 0 : (long) b.width() * b.height();
    }

    /** 列表、网格、滚动视图这类容器的类名。 */
    static boolean isListClass(String className) {
        return className != null && (className.endsWith("RecyclerView") || className.endsWith("ListView")
                || className.endsWith("GridView") || className.endsWith("ScrollView")
                || className.contains("ViewPager"));
    }

    /** 左右翻页的容器：ViewPager 本身，或者 ViewPager2 里面那个横向的 RecyclerView。 */
    private static boolean isPager(UiNode node) {
        String cls = node.getClassName();
        UiNode parent = node.getParent();
        return cls != null && (cls.contains("ViewPager") || cls.endsWith("HorizontalScrollView"))
                || parent != null && parent.getClassName() != null && parent.getClassName().contains("ViewPager");
    }

    private static int clampX(Coord c, ScreenSnapshot screen) {
        return clamp(c.resolveX(screen.getScreenWidth()), screen.getScreenWidth());
    }

    private static int clampY(Coord c, ScreenSnapshot screen) {
        return clamp(c.resolveY(screen.getScreenHeight()), screen.getScreenHeight());
    }

    private static int clamp(int value, int size) {
        if (size <= 0) {
            return Math.max(0, value);
        }
        return Math.max(0, Math.min(size - 1, value));
    }

    /** 提供给自定义动作的环境，所有操作都基于它持有的“当前快照”，并记入运行轨迹。 */
    private final class Context implements ActionContext {

        private final String stateId;
        private final String label;
        private ScreenSnapshot screen;

        Context(ScreenSnapshot screen, String stateId, String label) {
            this.screen = screen;
            this.stateId = stateId;
            this.label = label;
        }

        @Override
        public ScreenSnapshot screen() {
            return screen;
        }

        @Override
        public ScreenSnapshot refresh() {
            ScreenSnapshot fresh = source.snapshot();
            if (fresh != null) {
                screen = fresh;
            }
            return fresh;
        }

        @Override
        public boolean perform(Action action) {
            if (action instanceof Action.Custom || action instanceof Action.Inline) {
                throw new IllegalArgumentException("custom and run(...) actions cannot be nested");
            }
            return trace(action.toString(), run(action, screen, stateId));
        }

        @Override
        public boolean click(UiNode node) {
            return trace("click " + node,
                    ActionRunner.this.click(node, Action.ClickMode.AUTO, false, screen));
        }

        @Override
        public void sleep(long ms) {
            waiter.sleep(ms);
        }

        @Override
        public boolean isCancelled() {
            return waiter.isCancelled();
        }

        private boolean trace(String what, ActionResult r) {
            boolean failed = r.getStatus() == ActionResult.Status.HARD_FAIL
                    || r.getStatus() == ActionResult.Status.ERROR;
            tracer.event(TraceEvent.Type.ACTION, failed ? ApexLogger.ERROR : ApexLogger.DEBUG, stateId,
                    "  " + label + " > " + what + " -> " + r);
            return r.isOk();
        }
    }
}

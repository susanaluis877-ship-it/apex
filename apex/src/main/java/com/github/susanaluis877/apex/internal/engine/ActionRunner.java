package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.ApexLogger;
import com.github.susanaluis877.apex.TraceEvent;
import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.action.ActionContext;
import com.github.susanaluis877.apex.action.Actions;
import com.github.susanaluis877.apex.action.Coord;
import com.github.susanaluis877.apex.action.CustomAction;
import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.node.Bounds;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

/**
 * 把 {@link Action} 描述翻译成设备操作：在快照里找目标、找可点击的祖先、必要时退回坐标手势。
 *
 * <p>成功的结果都带上实际做了什么（哪个节点、哪种方式、坐标），供运行轨迹使用。
 */
final class ActionRunner {

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
            return custom((Action.Custom) action, screen, stateId);
        }
        return ActionResult.error("unsupported action: " + action);
    }

    ActionResult click(UiNode node, Action.ClickMode mode, boolean longClick, ScreenSnapshot screen) {
        String nodeAction = longClick ? "ACTION_LONG_CLICK" : "ACTION_CLICK";
        String nodeFailure = null;
        if (mode != Action.ClickMode.GESTURE) {
            UiNode clickable = clickableSelfOrAncestor(node, longClick);
            if (clickable != null) {
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
        int x = clamp(b.centerX(), screen.getScreenWidth());
        int y = clamp(b.centerY(), screen.getScreenHeight());
        long duration = longClick ? Actions.DEFAULT_LONG_PRESS_MS : Actions.DEFAULT_TAP_MS;
        String detail = (longClick ? "long-press" : "tap") + " (" + x + "," + y + ") " + duration + "ms on " + node
                + (nodeFailure != null ? " after " + nodeFailure : "");
        return detail(executor.gesture(x, y, x, y, duration), detail);
    }

    private ActionResult scroll(Action.Scroll scroll, ScreenSnapshot screen) {
        UiNode node;
        if (scroll.getTarget() == null) {
            node = null;
            for (UiNode n : screen.nodes()) {
                if (n.isVisible() && n.isScrollable()) {
                    node = n;
                    break;
                }
            }
            if (node == null) {
                return ActionResult.soft("no scrollable node on screen");
            }
        } else {
            node = scroll.getTarget().findFirst(screen);
            if (node == null) {
                return ActionResult.soft("target not found: " + scroll.getTarget().explainMismatch(screen));
            }
            while (node != null && !node.isScrollable()) {
                node = node.getParent();
            }
            if (node == null) {
                return ActionResult.soft("target has no scrollable container: " + scroll.getTarget());
            }
        }
        return detail(executor.scrollNode(node, scroll.getDirection()),
                "ACTION_SCROLL_" + scroll.getDirection() + " on " + node);
    }

    private ActionResult custom(Action.Custom action, ScreenSnapshot screen, String stateId) {
        CustomAction custom = Registry.get().action(action.getName());
        if (custom == null) {
            return ActionResult.error("custom action '" + action.getName() + "' is not registered");
        }
        Context context = new Context(screen, stateId, action.getName());
        try {
            if (custom.perform(context, action.getArgs())) {
                return ActionResult.ok("custom '" + action.getName() + "' returned true");
            }
            return waiter.isCancelled()
                    ? ActionResult.cancelled()
                    : ActionResult.soft("custom action '" + action.getName() + "' returned false");
        } catch (Exception e) {
            return ActionResult.error("custom action '" + action.getName() + "' threw " + e);
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
        private final String name;
        private ScreenSnapshot screen;

        Context(ScreenSnapshot screen, String stateId, String name) {
            this.screen = screen;
            this.stateId = stateId;
            this.name = name;
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
            if (action instanceof Action.Custom) {
                throw new IllegalArgumentException("nested custom actions are not supported");
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
                    "  custom '" + name + "' > " + what + " -> " + r);
            return r.isOk();
        }
    }
}

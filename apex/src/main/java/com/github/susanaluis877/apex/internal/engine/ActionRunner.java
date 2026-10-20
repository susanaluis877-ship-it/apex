package com.github.susanaluis877.apex.internal.engine;

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
 */
final class ActionRunner {

    private final ScreenSource source;
    private final ActionExecutor executor;
    private final Waiter waiter;

    ActionRunner(ScreenSource source, ActionExecutor executor, Waiter waiter) {
        this.source = source;
        this.executor = executor;
        this.waiter = waiter;
    }

    ActionResult run(Action action, ScreenSnapshot screen) {
        if (action.getDelayMs() > 0) {
            waiter.sleep(action.getDelayMs());
            if (waiter.isCancelled()) {
                return ActionResult.cancelled();
            }
            screen = source.snapshot();
            if (screen == null) {
                return ActionResult.soft("service unavailable");
            }
        }
        if (action instanceof Action.Click) {
            Action.Click click = (Action.Click) action;
            UiNode node = click.getTarget().findFirst(screen);
            if (node == null) {
                return ActionResult.soft("target not found: " + click.getTarget());
            }
            return click(node, click.getMode(), click.isLongClick(), screen);
        }
        if (action instanceof Action.Tap) {
            Action.Tap tap = (Action.Tap) action;
            int x = clampX(tap.getPoint(), screen);
            int y = clampY(tap.getPoint(), screen);
            return executor.gesture(x, y, x, y, tap.getDurationMs());
        }
        if (action instanceof Action.Swipe) {
            Action.Swipe swipe = (Action.Swipe) action;
            return executor.gesture(clampX(swipe.getFrom(), screen), clampY(swipe.getFrom(), screen),
                    clampX(swipe.getTo(), screen), clampY(swipe.getTo(), screen), swipe.getDurationMs());
        }
        if (action instanceof Action.Scroll) {
            return scroll((Action.Scroll) action, screen);
        }
        if (action instanceof Action.SetText) {
            Action.SetText setText = (Action.SetText) action;
            UiNode node = setText.getTarget().findFirst(screen);
            if (node == null) {
                return ActionResult.soft("target not found: " + setText.getTarget());
            }
            if (!node.isEditable()) {
                return ActionResult.hard("target is not editable: " + node);
            }
            return executor.setText(node, setText.getText());
        }
        if (action instanceof Action.Global) {
            return executor.global(((Action.Global) action).getKind());
        }
        if (action instanceof Action.LaunchApp) {
            return executor.launchApp(((Action.LaunchApp) action).getPackageName());
        }
        if (action instanceof Action.Sleep) {
            waiter.sleep(((Action.Sleep) action).getMs());
            return waiter.isCancelled() ? ActionResult.cancelled() : ActionResult.OK;
        }
        if (action instanceof Action.Custom) {
            return custom((Action.Custom) action, screen);
        }
        return ActionResult.error("unsupported action: " + action);
    }

    ActionResult click(UiNode node, Action.ClickMode mode, boolean longClick, ScreenSnapshot screen) {
        if (mode != Action.ClickMode.GESTURE) {
            UiNode clickable = clickableSelfOrAncestor(node, longClick);
            if (clickable != null) {
                ActionResult r = executor.clickNode(clickable, longClick);
                if (r.isOk() || mode == Action.ClickMode.NODE) {
                    return r;
                }
            } else if (mode == Action.ClickMode.NODE) {
                return ActionResult.soft("no " + (longClick ? "long-" : "") + "clickable node for " + node);
            }
        }
        Bounds b = node.getBounds();
        if (b.isEmpty()) {
            return ActionResult.soft("target has empty bounds: " + node);
        }
        int x = clamp(b.centerX(), screen.getScreenWidth());
        int y = clamp(b.centerY(), screen.getScreenHeight());
        return executor.gesture(x, y, x, y, longClick ? Actions.DEFAULT_LONG_PRESS_MS : Actions.DEFAULT_TAP_MS);
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
                return ActionResult.soft("target not found: " + scroll.getTarget());
            }
            while (node != null && !node.isScrollable()) {
                node = node.getParent();
            }
            if (node == null) {
                return ActionResult.soft("target has no scrollable container: " + scroll.getTarget());
            }
        }
        return executor.scrollNode(node, scroll.getDirection());
    }

    private ActionResult custom(Action.Custom action, ScreenSnapshot screen) {
        CustomAction custom = Registry.get().action(action.getName());
        if (custom == null) {
            return ActionResult.error("custom action '" + action.getName() + "' is not registered");
        }
        Context context = new Context(screen);
        try {
            if (custom.perform(context, action.getArgs())) {
                return ActionResult.OK;
            }
            return waiter.isCancelled()
                    ? ActionResult.cancelled()
                    : ActionResult.soft("custom action '" + action.getName() + "' returned false");
        } catch (Exception e) {
            return ActionResult.error("custom action '" + action.getName() + "' threw " + e);
        }
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

    /** 提供给自定义动作的环境，所有操作都基于它持有的“当前快照”。 */
    private final class Context implements ActionContext {

        private ScreenSnapshot screen;

        Context(ScreenSnapshot screen) {
            this.screen = screen;
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
            return run(action, screen).isOk();
        }

        @Override
        public boolean click(UiNode node) {
            return ActionRunner.this.click(node, Action.ClickMode.AUTO, false, screen).isOk();
        }

        @Override
        public void sleep(long ms) {
            waiter.sleep(ms);
        }

        @Override
        public boolean isCancelled() {
            return waiter.isCancelled();
        }
    }
}

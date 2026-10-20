package com.github.susanaluis877.apex.action;

import com.github.susanaluis877.apex.match.Selector;

import java.util.Collections;
import java.util.Map;

/**
 * {@link Action} 的工厂，建议静态导入使用。
 */
public final class Actions {

    /** 坐标点击的默认按压时长。 */
    public static final long DEFAULT_TAP_MS = 50;
    /** 手势长按的默认按压时长。 */
    public static final long DEFAULT_LONG_PRESS_MS = 800;
    /** 滑动的默认时长。 */
    public static final long DEFAULT_SWIPE_MS = 300;

    private Actions() {
    }

    public static Action click(Selector target) {
        return click(target, Action.ClickMode.AUTO);
    }

    public static Action click(Selector target, Action.ClickMode mode) {
        return new Action.Click(target, mode, false, false, 0);
    }

    public static Action longClick(Selector target) {
        return longClick(target, Action.ClickMode.AUTO);
    }

    public static Action longClick(Selector target, Action.ClickMode mode) {
        return new Action.Click(target, mode, true, false, 0);
    }

    /** 按绝对像素坐标点击。 */
    public static Action tap(int x, int y) {
        return tap(Coord.px(x, y), DEFAULT_TAP_MS);
    }

    /** 按屏幕比例坐标点击，例如 {@code tapRatio(0.5, 0.9)} 点击底部居中位置。 */
    public static Action tapRatio(double x, double y) {
        return tap(Coord.ratio(x, y), DEFAULT_TAP_MS);
    }

    public static Action tap(Coord point, long durationMs) {
        return new Action.Tap(point, durationMs, false, 0);
    }

    public static Action swipe(int fromX, int fromY, int toX, int toY) {
        return swipe(Coord.px(fromX, fromY), Coord.px(toX, toY), DEFAULT_SWIPE_MS);
    }

    public static Action swipeRatio(double fromX, double fromY, double toX, double toY) {
        return swipe(Coord.ratio(fromX, fromY), Coord.ratio(toX, toY), DEFAULT_SWIPE_MS);
    }

    public static Action swipe(Coord from, Coord to, long durationMs) {
        return new Action.Swipe(from, to, durationMs, false, 0);
    }

    /** 滚动屏幕上第一个可见的可滚动节点。 */
    public static Action scroll(Action.Direction direction) {
        return new Action.Scroll(null, direction, false, 0);
    }

    public static Action scroll(Selector target, Action.Direction direction) {
        return new Action.Scroll(target, direction, false, 0);
    }

    public static Action setText(Selector target, String text) {
        return new Action.SetText(target, text, false, 0);
    }

    public static Action back() {
        return new Action.Global(Action.GlobalAction.BACK, false, 0);
    }

    public static Action home() {
        return new Action.Global(Action.GlobalAction.HOME, false, 0);
    }

    public static Action recents() {
        return new Action.Global(Action.GlobalAction.RECENTS, false, 0);
    }

    public static Action notifications() {
        return new Action.Global(Action.GlobalAction.NOTIFICATIONS, false, 0);
    }

    /**
     * 启动指定 App。Android 11 及以上，宿主需要在 manifest 的 {@code <queries>} 里声明该包名。
     */
    public static Action launchApp(String packageName) {
        return new Action.LaunchApp(packageName, false, 0);
    }

    public static Action sleep(long ms) {
        return new Action.Sleep(ms, false, 0);
    }

    /**
     * 用代码写的动作，函数拿到 {@link ActionContext}，可以读取快照、点击节点、执行内置动作、等待。
     * 返回 true 表示成功，false 表示软失败。只能在 Java DSL 里使用；两个这样的动作只有是同一个函数对象时才相等。
     */
    public static Action run(ActionFunction function) {
        return new Action.Inline(function, false, 0);
    }

    /** 引用通过 {@code Apex.registerAction} 注册的自定义动作，JSON 里的 {@code "custom"} 对应这个写法。 */
    public static Action custom(String name) {
        return custom(name, Collections.emptyMap());
    }

    public static Action custom(String name, Map<String, String> args) {
        return new Action.Custom(name, args, false, 0);
    }
}

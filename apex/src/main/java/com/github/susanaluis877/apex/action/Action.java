package com.github.susanaluis877.apex.action;

import com.github.susanaluis877.apex.match.Selector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 状态或拦截器里要执行的一个动作。只描述“做什么”，不包含执行逻辑；通过 {@link Actions} 创建，不可变。
 *
 * <p>所有动作都支持两个通用选项：
 * <ul>
 *   <li>{@link #optional()}：找不到目标或执行失败时直接跳过，继续下一个动作。</li>
 *   <li>{@link #delayMs(long)}：执行前先等待一段时间。</li>
 * </ul>
 */
public abstract class Action {

    /** 点击方式。 */
    public enum ClickMode {
        /** 先对节点或最近的可点击祖先执行 ACTION_CLICK，失败再按节点中心坐标手势点击。 */
        AUTO,
        /** 只用节点动作。 */
        NODE,
        /** 只按节点中心坐标手势点击，适合自绘 View、WebView、Flutter。 */
        GESTURE
    }

    /** 滚动方向。forward/backward 适用于任意可滚动容器，up/down/left/right 需要容器支持对应动作。 */
    public enum Direction {
        FORWARD, BACKWARD, UP, DOWN, LEFT, RIGHT
    }

    /** 全局动作。 */
    public enum GlobalAction {
        BACK, HOME, RECENTS, NOTIFICATIONS
    }

    final boolean optional;
    final long delayMs;

    Action(boolean optional, long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0");
        }
        this.optional = optional;
        this.delayMs = delayMs;
    }

    public boolean isOptional() {
        return optional;
    }

    public long getDelayMs() {
        return delayMs;
    }

    /** 返回一个失败时跳过的副本。 */
    public Action optional() {
        return copy(true, delayMs);
    }

    /** 返回一个执行前先等待 {@code ms} 毫秒的副本。 */
    public Action delayMs(long ms) {
        return copy(optional, ms);
    }

    abstract Action copy(boolean optional, long delayMs);

    abstract String describe();

    boolean baseEquals(Action other) {
        return optional == other.optional && delayMs == other.delayMs;
    }

    int baseHash() {
        return Boolean.hashCode(optional) * 31 + Long.hashCode(delayMs);
    }

    @Override
    public String toString() {
        String s = describe();
        if (optional) {
            s += "?";
        }
        if (delayMs > 0) {
            s += "+" + delayMs + "ms";
        }
        return s;
    }

    /** 点击或长按节点。 */
    public static final class Click extends Action {
        private final Selector target;
        private final ClickMode mode;
        private final boolean longClick;

        Click(Selector target, ClickMode mode, boolean longClick, boolean optional, long delayMs) {
            super(optional, delayMs);
            this.target = Objects.requireNonNull(target, "target");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.longClick = longClick;
        }

        public Selector getTarget() {
            return target;
        }

        public ClickMode getMode() {
            return mode;
        }

        public boolean isLongClick() {
            return longClick;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new Click(target, mode, longClick, optional, delayMs);
        }

        @Override
        String describe() {
            return (longClick ? "longClick" : "click") + target + (mode == ClickMode.AUTO ? "" : "/" + mode);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Click)) {
                return false;
            }
            Click c = (Click) o;
            return baseEquals(c) && target.equals(c.target) && mode == c.mode && longClick == c.longClick;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), target, mode, longClick);
        }
    }

    /** 按坐标点击。 */
    public static final class Tap extends Action {
        private final Coord point;
        private final long durationMs;

        Tap(Coord point, long durationMs, boolean optional, long delayMs) {
            super(optional, delayMs);
            this.point = Objects.requireNonNull(point, "point");
            if (durationMs <= 0) {
                throw new IllegalArgumentException("durationMs must be > 0");
            }
            this.durationMs = durationMs;
        }

        public Coord getPoint() {
            return point;
        }

        public long getDurationMs() {
            return durationMs;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new Tap(point, durationMs, optional, delayMs);
        }

        @Override
        String describe() {
            return "tap" + point;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Tap)) {
                return false;
            }
            Tap t = (Tap) o;
            return baseEquals(t) && point.equals(t.point) && durationMs == t.durationMs;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), point, durationMs);
        }
    }

    /** 从一个坐标滑到另一个坐标。 */
    public static final class Swipe extends Action {
        private final Coord from;
        private final Coord to;
        private final long durationMs;

        Swipe(Coord from, Coord to, long durationMs, boolean optional, long delayMs) {
            super(optional, delayMs);
            this.from = Objects.requireNonNull(from, "from");
            this.to = Objects.requireNonNull(to, "to");
            if (durationMs <= 0) {
                throw new IllegalArgumentException("durationMs must be > 0");
            }
            this.durationMs = durationMs;
        }

        public Coord getFrom() {
            return from;
        }

        public Coord getTo() {
            return to;
        }

        public long getDurationMs() {
            return durationMs;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new Swipe(from, to, durationMs, optional, delayMs);
        }

        @Override
        String describe() {
            return "swipe" + from + "->" + to;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Swipe)) {
                return false;
            }
            Swipe s = (Swipe) o;
            return baseEquals(s) && from.equals(s.from) && to.equals(s.to) && durationMs == s.durationMs;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), from, to, durationMs);
        }
    }

    /** 滚动容器。target 为 null 时滚动屏幕上第一个可见的可滚动节点。 */
    public static final class Scroll extends Action {
        private final Selector target;
        private final Direction direction;

        Scroll(Selector target, Direction direction, boolean optional, long delayMs) {
            super(optional, delayMs);
            this.target = target;
            this.direction = Objects.requireNonNull(direction, "direction");
        }

        public Selector getTarget() {
            return target;
        }

        public Direction getDirection() {
            return direction;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new Scroll(target, direction, optional, delayMs);
        }

        @Override
        String describe() {
            return "scroll(" + direction.name().toLowerCase(Locale.ROOT) + ")" + (target != null ? target : "");
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Scroll)) {
                return false;
            }
            Scroll s = (Scroll) o;
            return baseEquals(s) && Objects.equals(target, s.target) && direction == s.direction;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), target, direction);
        }
    }

    /** 给输入框设置文本（替换原有内容）。 */
    public static final class SetText extends Action {
        private final Selector target;
        private final String text;

        SetText(Selector target, String text, boolean optional, long delayMs) {
            super(optional, delayMs);
            this.target = Objects.requireNonNull(target, "target");
            this.text = Objects.requireNonNull(text, "text");
        }

        public Selector getTarget() {
            return target;
        }

        public String getText() {
            return text;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new SetText(target, text, optional, delayMs);
        }

        @Override
        String describe() {
            return "setText" + target + "=\"" + text + "\"";
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SetText)) {
                return false;
            }
            SetText s = (SetText) o;
            return baseEquals(s) && target.equals(s.target) && text.equals(s.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), target, text);
        }
    }

    /** 返回、Home、最近任务、通知栏。 */
    public static final class Global extends Action {
        private final GlobalAction kind;

        Global(GlobalAction kind, boolean optional, long delayMs) {
            super(optional, delayMs);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        public GlobalAction getKind() {
            return kind;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new Global(kind, optional, delayMs);
        }

        @Override
        String describe() {
            return kind.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Global && baseEquals((Global) o) && kind == ((Global) o).kind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), kind);
        }
    }

    /** 启动 App 的启动页。 */
    public static final class LaunchApp extends Action {
        private final String packageName;

        LaunchApp(String packageName, boolean optional, long delayMs) {
            super(optional, delayMs);
            this.packageName = Objects.requireNonNull(packageName, "packageName");
        }

        public String getPackageName() {
            return packageName;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new LaunchApp(packageName, optional, delayMs);
        }

        @Override
        String describe() {
            return "launchApp(" + packageName + ")";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof LaunchApp && baseEquals((LaunchApp) o)
                    && packageName.equals(((LaunchApp) o).packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), packageName);
        }
    }

    /** 等待一段时间，可被取消打断。 */
    public static final class Sleep extends Action {
        private final long ms;

        Sleep(long ms, boolean optional, long delayMs) {
            super(optional, delayMs);
            if (ms <= 0) {
                throw new IllegalArgumentException("ms must be > 0");
            }
            this.ms = ms;
        }

        public long getMs() {
            return ms;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new Sleep(ms, optional, delayMs);
        }

        @Override
        String describe() {
            return "sleep(" + ms + ")";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Sleep && baseEquals((Sleep) o) && ms == ((Sleep) o).ms;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), ms);
        }
    }

    /** 用代码写的动作，见 {@link Actions#run(ActionFunction)}。 */
    public static final class Inline extends Action {
        private final ActionFunction function;

        Inline(ActionFunction function, boolean optional, long delayMs) {
            super(optional, delayMs);
            this.function = Objects.requireNonNull(function, "function");
        }

        public ActionFunction getFunction() {
            return function;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new Inline(function, optional, delayMs);
        }

        @Override
        String describe() {
            return "run(<inline>)";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Inline && baseEquals((Inline) o) && function == ((Inline) o).function;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), System.identityHashCode(function));
        }
    }

    /** 引用通过 {@code Apex.registerAction} 注册的自定义动作。 */
    public static final class Custom extends Action {
        private final String name;
        private final Map<String, String> args;

        Custom(String name, Map<String, String> args, boolean optional, long delayMs) {
            super(optional, delayMs);
            this.name = Objects.requireNonNull(name, "name");
            this.args = args == null || args.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }

        public String getName() {
            return name;
        }

        public Map<String, String> getArgs() {
            return args;
        }

        @Override
        Action copy(boolean optional, long delayMs) {
            return new Custom(name, args, optional, delayMs);
        }

        @Override
        String describe() {
            return "custom(" + name + (args.isEmpty() ? "" : ", " + args) + ")";
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Custom)) {
                return false;
            }
            Custom c = (Custom) o;
            return baseEquals(c) && name.equals(c.name) && args.equals(c.args);
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseHash(), name, args);
        }
    }
}

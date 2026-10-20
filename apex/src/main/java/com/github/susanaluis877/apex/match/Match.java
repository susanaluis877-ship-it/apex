package com.github.susanaluis877.apex.match;

import com.github.susanaluis877.apex.internal.Registry;
import com.github.susanaluis877.apex.node.ScreenSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ScreenMatcher} 的工厂。
 *
 * <p>{@code allOf}/{@code anyOf} 会把同类嵌套展开、只有一个元素时直接返回该元素；
 * {@code has(a, b)} 等价于 {@code allOf(has(a), has(b))}。因此 DSL 和 JSON 写出的同一个条件得到相等的对象。
 */
public final class Match {

    private Match() {
    }

    /** 最上层应用窗口属于指定包名。 */
    public static ScreenMatcher pkg(String packageName) {
        return new Package(Objects.requireNonNull(packageName, "packageName"));
    }

    /**
     * 当前 Activity 类名。写全名时精确匹配，以 {@code .} 开头时匹配后缀，不含 {@code .} 时匹配简单类名。
     *
     * <p>只是辅助条件：单 Activity、Compose、Flutter 应用里它不区分页面，优先用 {@link #has(Selector...)}。
     */
    public static ScreenMatcher activity(String activity) {
        return new Activity(Objects.requireNonNull(activity, "activity"));
    }

    /** 屏幕上存在匹配每个选择器的节点。 */
    public static ScreenMatcher has(Selector... selectors) {
        if (selectors.length == 0) {
            throw new IllegalArgumentException("has() needs at least one selector");
        }
        List<ScreenMatcher> list = new ArrayList<>(selectors.length);
        for (Selector selector : selectors) {
            list.add(new Has(Objects.requireNonNull(selector, "selector")));
        }
        return allOf(list);
    }

    public static ScreenMatcher not(ScreenMatcher matcher) {
        return new Not(Objects.requireNonNull(matcher, "matcher"));
    }

    public static ScreenMatcher allOf(ScreenMatcher... matchers) {
        return allOf(Arrays.asList(matchers));
    }

    public static ScreenMatcher allOf(List<ScreenMatcher> matchers) {
        List<ScreenMatcher> flat = new ArrayList<>();
        for (ScreenMatcher m : matchers) {
            Objects.requireNonNull(m, "matcher");
            if (m instanceof AllOf) {
                flat.addAll(((AllOf) m).items);
            } else {
                flat.add(m);
            }
        }
        if (flat.isEmpty()) {
            throw new IllegalArgumentException("allOf() needs at least one matcher");
        }
        return flat.size() == 1 ? flat.get(0) : new AllOf(flat);
    }

    public static ScreenMatcher anyOf(ScreenMatcher... matchers) {
        return anyOf(Arrays.asList(matchers));
    }

    public static ScreenMatcher anyOf(List<ScreenMatcher> matchers) {
        List<ScreenMatcher> flat = new ArrayList<>();
        for (ScreenMatcher m : matchers) {
            Objects.requireNonNull(m, "matcher");
            if (m instanceof AnyOf) {
                flat.addAll(((AnyOf) m).items);
            } else {
                flat.add(m);
            }
        }
        if (flat.isEmpty()) {
            throw new IllegalArgumentException("anyOf() needs at least one matcher");
        }
        return flat.size() == 1 ? flat.get(0) : new AnyOf(flat);
    }

    /** 任何屏幕都匹配，常用于起始状态，例如先启动目标 App。 */
    public static ScreenMatcher always() {
        return Always.INSTANCE;
    }

    /**
     * 用代码写的条件，函数返回 true 表示匹配。可以和其他条件任意组合，例如
     * {@code allOf(pkg("com.foo"), custom(screen -> ...))}。
     *
     * <p>只能在 Java DSL 里使用。两个内联条件只有是同一个函数对象时才相等。
     */
    public static ScreenMatcher custom(ScreenCondition condition) {
        return new Inline(Objects.requireNonNull(condition, "condition"));
    }

    /** 引用通过 {@code Apex.registerMatcher} 注册的自定义条件，JSON 里的 {@code "custom"} 对应这个写法。 */
    public static ScreenMatcher custom(String name) {
        return custom(name, Collections.emptyMap());
    }

    public static ScreenMatcher custom(String name, Map<String, String> args) {
        return new Custom(Objects.requireNonNull(name, "name"), args);
    }

    private static final class Package extends ScreenMatcher {
        private final String packageName;

        Package(String packageName) {
            this.packageName = packageName;
        }

        @Override
        public boolean matches(ScreenSnapshot screen) {
            return packageName.equals(screen.getPackageName());
        }

        @Override
        public void collectCustomNames(Set<String> out) {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Package && packageName.equals(((Package) o).packageName);
        }

        @Override
        public int hashCode() {
            return packageName.hashCode();
        }

        @Override
        public String toString() {
            return "package=\"" + packageName + "\"";
        }
    }

    private static final class Activity extends ScreenMatcher {
        private final String activity;

        Activity(String activity) {
            this.activity = activity;
        }

        @Override
        public boolean matches(ScreenSnapshot screen) {
            String current = screen.getActivity();
            if (current == null) {
                return false;
            }
            if (activity.startsWith(".")) {
                return current.endsWith(activity);
            }
            if (activity.indexOf('.') < 0) {
                return current.endsWith("." + activity) || current.equals(activity);
            }
            return current.equals(activity);
        }

        @Override
        public void collectCustomNames(Set<String> out) {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Activity && activity.equals(((Activity) o).activity);
        }

        @Override
        public int hashCode() {
            return activity.hashCode() * 3;
        }

        @Override
        public String toString() {
            return "activity=\"" + activity + "\"";
        }
    }

    private static final class Has extends ScreenMatcher {
        private final Selector selector;

        Has(Selector selector) {
            this.selector = selector;
        }

        @Override
        public boolean matches(ScreenSnapshot screen) {
            return selector.findFirst(screen) != null;
        }

        @Override
        public void collectCustomNames(Set<String> out) {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Has && selector.equals(((Has) o).selector);
        }

        @Override
        public int hashCode() {
            return selector.hashCode() * 5;
        }

        @Override
        public String toString() {
            return "has" + selector;
        }
    }

    private static final class Not extends ScreenMatcher {
        private final ScreenMatcher matcher;

        Not(ScreenMatcher matcher) {
            this.matcher = matcher;
        }

        @Override
        public boolean matches(ScreenSnapshot screen) {
            return !matcher.matches(screen);
        }

        @Override
        public void collectCustomNames(Set<String> out) {
            matcher.collectCustomNames(out);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Not && matcher.equals(((Not) o).matcher);
        }

        @Override
        public int hashCode() {
            return matcher.hashCode() * 7;
        }

        @Override
        public String toString() {
            return "not(" + matcher + ")";
        }
    }

    private static final class AllOf extends ScreenMatcher {
        private final List<ScreenMatcher> items;

        AllOf(List<ScreenMatcher> items) {
            this.items = Collections.unmodifiableList(items);
        }

        @Override
        public boolean matches(ScreenSnapshot screen) {
            for (ScreenMatcher m : items) {
                if (!m.matches(screen)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void collectCustomNames(Set<String> out) {
            for (ScreenMatcher m : items) {
                m.collectCustomNames(out);
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AllOf && items.equals(((AllOf) o).items);
        }

        @Override
        public int hashCode() {
            return items.hashCode() * 11;
        }

        @Override
        public String toString() {
            return "allOf" + items;
        }
    }

    private static final class AnyOf extends ScreenMatcher {
        private final List<ScreenMatcher> items;

        AnyOf(List<ScreenMatcher> items) {
            this.items = Collections.unmodifiableList(items);
        }

        @Override
        public boolean matches(ScreenSnapshot screen) {
            for (ScreenMatcher m : items) {
                if (m.matches(screen)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void collectCustomNames(Set<String> out) {
            for (ScreenMatcher m : items) {
                m.collectCustomNames(out);
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AnyOf && items.equals(((AnyOf) o).items);
        }

        @Override
        public int hashCode() {
            return items.hashCode() * 13;
        }

        @Override
        public String toString() {
            return "anyOf" + items;
        }
    }

    private static final class Always extends ScreenMatcher {
        static final Always INSTANCE = new Always();

        @Override
        public boolean matches(ScreenSnapshot screen) {
            return true;
        }

        @Override
        public void collectCustomNames(Set<String> out) {
        }

        @Override
        public String toString() {
            return "always";
        }
    }

    private static final class Inline extends ScreenMatcher {
        private final ScreenCondition condition;

        Inline(ScreenCondition condition) {
            this.condition = condition;
        }

        @Override
        public boolean matches(ScreenSnapshot screen) {
            return condition.test(screen);
        }

        @Override
        public void collectCustomNames(Set<String> out) {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Inline && condition == ((Inline) o).condition;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(condition);
        }

        @Override
        public String toString() {
            return "custom(<inline>)";
        }
    }

    private static final class Custom extends ScreenMatcher {
        private final String name;
        private final Map<String, String> args;

        Custom(String name, Map<String, String> args) {
            this.name = name;
            this.args = args == null || args.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }

        @Override
        public boolean matches(ScreenSnapshot screen) {
            CustomMatcher matcher = Registry.get().matcher(name);
            if (matcher == null) {
                throw new IllegalStateException("custom matcher '" + name + "' is not registered");
            }
            return matcher.matches(screen, args);
        }

        @Override
        public void collectCustomNames(Set<String> out) {
            out.add(name);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Custom)) {
                return false;
            }
            Custom c = (Custom) o;
            return name.equals(c.name) && args.equals(c.args);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 17 + args.hashCode();
        }

        @Override
        public String toString() {
            return "custom(" + name + (args.isEmpty() ? "" : ", " + args) + ")";
        }
    }
}

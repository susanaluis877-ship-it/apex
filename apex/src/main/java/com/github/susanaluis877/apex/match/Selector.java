package com.github.susanaluis877.apex.match;

import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 节点选择器，所有条件之间是 AND 关系。通过 {@link By} 创建，不可变，每个设置方法都返回新对象。
 *
 * <ul>
 *   <li>{@code id} 写短名（{@code submit}）时匹配任意包下的 {@code :id/submit}，写全名时精确匹配。</li>
 *   <li>{@code className} 写简单类名（{@code Button}）时匹配类名最后一段，写全名时精确匹配。</li>
 *   <li>{@code textMatches}/{@code descMatches} 要求整段文本匹配正则。</li>
 *   <li>默认只匹配用户可见的节点，{@code visible(false)} 后可见与不可见都匹配。</li>
 * </ul>
 */
public final class Selector {

    private String text;
    private String textContains;
    private String textMatches;
    private String desc;
    private String descContains;
    private String descMatches;
    private String id;
    private String className;
    private Boolean clickable;
    private Boolean enabled;
    private Boolean checked;
    private Boolean selected;
    private Boolean scrollable;
    private boolean visibleOnly = true;

    private Pattern textPattern;
    private Pattern descPattern;
    /** 由字段派生的条件列表，首次使用时构建；每次修改都会复制出新对象，所以不需要失效。 */
    private List<Cond> conds;

    Selector() {
    }

    private Selector copy() {
        Selector s = new Selector();
        s.text = text;
        s.textContains = textContains;
        s.textMatches = textMatches;
        s.desc = desc;
        s.descContains = descContains;
        s.descMatches = descMatches;
        s.id = id;
        s.className = className;
        s.clickable = clickable;
        s.enabled = enabled;
        s.checked = checked;
        s.selected = selected;
        s.scrollable = scrollable;
        s.visibleOnly = visibleOnly;
        s.textPattern = textPattern;
        s.descPattern = descPattern;
        return s;
    }

    public Selector text(String value) {
        Selector s = copy();
        s.text = Objects.requireNonNull(value, "text");
        return s;
    }

    public Selector textContains(String value) {
        Selector s = copy();
        s.textContains = Objects.requireNonNull(value, "textContains");
        return s;
    }

    /**
     * @throws java.util.regex.PatternSyntaxException 正则写错时
     */
    public Selector textMatches(String regex) {
        Selector s = copy();
        s.textPattern = Pattern.compile(Objects.requireNonNull(regex, "textMatches"));
        s.textMatches = regex;
        return s;
    }

    public Selector desc(String value) {
        Selector s = copy();
        s.desc = Objects.requireNonNull(value, "desc");
        return s;
    }

    public Selector descContains(String value) {
        Selector s = copy();
        s.descContains = Objects.requireNonNull(value, "descContains");
        return s;
    }

    /**
     * @throws java.util.regex.PatternSyntaxException 正则写错时
     */
    public Selector descMatches(String regex) {
        Selector s = copy();
        s.descPattern = Pattern.compile(Objects.requireNonNull(regex, "descMatches"));
        s.descMatches = regex;
        return s;
    }

    public Selector id(String value) {
        Selector s = copy();
        s.id = Objects.requireNonNull(value, "id");
        return s;
    }

    public Selector className(String value) {
        Selector s = copy();
        s.className = Objects.requireNonNull(value, "className");
        return s;
    }

    public Selector clickable(boolean value) {
        Selector s = copy();
        s.clickable = value;
        return s;
    }

    public Selector enabled(boolean value) {
        Selector s = copy();
        s.enabled = value;
        return s;
    }

    public Selector checked(boolean value) {
        Selector s = copy();
        s.checked = value;
        return s;
    }

    public Selector selected(boolean value) {
        Selector s = copy();
        s.selected = value;
        return s;
    }

    public Selector scrollable(boolean value) {
        Selector s = copy();
        s.scrollable = value;
        return s;
    }

    /**
     * true（默认）只匹配可见节点；false 时可见与不可见都匹配。
     */
    public Selector visible(boolean visibleOnly) {
        Selector s = copy();
        s.visibleOnly = visibleOnly;
        return s;
    }

    public boolean matches(UiNode node) {
        for (Cond c : conditions()) {
            if (!c.test.test(node)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 说明这个选择器为什么在屏幕上找不到节点，用于日志和运行轨迹；能找到时返回 null。
     *
     * <p>优先报告“差一个条件”的节点，例如 {@code 1 node(s) match all but enabled=true, e.g. Button ... (enabled=false)}；
     * 否则报告每个文本/id/类名条件各命中多少个节点。
     */
    public String explainMismatch(ScreenSnapshot screen) {
        List<Cond> all = conditions();
        int[] hits = new int[all.size()];
        int[] nearMisses = new int[all.size()];
        UiNode[] examples = new UiNode[all.size()];
        boolean anyIdentity = hasIdentity(all);
        for (UiNode node : screen.nodes()) {
            int failed = -1;
            int failures = 0;
            boolean identityHit = false;
            for (int i = 0; i < all.size(); i++) {
                Cond c = all.get(i);
                if (c.test.test(node)) {
                    hits[i]++;
                    identityHit |= c.identity;
                } else {
                    failures++;
                    failed = i;
                }
            }
            if (failures == 0) {
                return null;
            }
            // 只有命中了文本/id/类名之类的条件，才算“差一点”；否则几乎所有节点都只差那一个条件
            if (failures == 1 && (identityHit || !anyIdentity)) {
                nearMisses[failed]++;
                if (examples[failed] == null) {
                    examples[failed] = node;
                }
            }
        }
        if (all.size() > 1) {
            int best = -1;
            for (int i = 0; i < all.size(); i++) {
                if (nearMisses[i] > 0 && (best < 0 || nearMisses[i] > nearMisses[best])) {
                    best = i;
                }
            }
            if (best >= 0) {
                Cond c = all.get(best);
                return nearMisses[best] + " node(s) match all but " + c.label + ", e.g. " + examples[best]
                        + " (" + c.actual.apply(examples[best]) + ")";
            }
        }
        // 没有“差一点”的节点：列出每个文本/id/类名条件各命中几个节点（没有这类条件时列出全部）
        List<String> counts = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Cond c = all.get(i);
            if (c.identity || !anyIdentity) {
                counts.add(c.label + ": " + hits[i] + " node(s)");
            }
        }
        return "no node matches (" + String.join(", ", counts) + ")";
    }

    private static boolean hasIdentity(List<Cond> conds) {
        for (Cond c : conds) {
            if (c.identity) {
                return true;
            }
        }
        return false;
    }

    /** 选择器的每个条件，matches 和 explainMismatch 共用同一份定义。 */
    private List<Cond> conditions() {
        List<Cond> list = conds;
        if (list != null) {
            return list;
        }
        list = new ArrayList<>();
        if (text != null) {
            list.add(new Cond(true, "text=" + quote(text), n -> text.equals(n.getText()),
                    n -> "text=" + quote(n.getText())));
        }
        if (textContains != null) {
            list.add(new Cond(true, "textContains=" + quote(textContains),
                    n -> n.getText() != null && n.getText().contains(textContains), n -> "text=" + quote(n.getText())));
        }
        if (textPattern != null) {
            list.add(new Cond(true, "textMatches=" + quote(textMatches),
                    n -> n.getText() != null && textPattern.matcher(n.getText()).matches(),
                    n -> "text=" + quote(n.getText())));
        }
        if (desc != null) {
            list.add(new Cond(true, "desc=" + quote(desc), n -> desc.equals(n.getDesc()),
                    n -> "desc=" + quote(n.getDesc())));
        }
        if (descContains != null) {
            list.add(new Cond(true, "descContains=" + quote(descContains),
                    n -> n.getDesc() != null && n.getDesc().contains(descContains), n -> "desc=" + quote(n.getDesc())));
        }
        if (descPattern != null) {
            list.add(new Cond(true, "descMatches=" + quote(descMatches),
                    n -> n.getDesc() != null && descPattern.matcher(n.getDesc()).matches(),
                    n -> "desc=" + quote(n.getDesc())));
        }
        if (id != null) {
            list.add(new Cond(true, "id=" + quote(id), n -> idMatches(n.getViewId()),
                    n -> "id=" + quote(n.getViewId())));
        }
        if (className != null) {
            list.add(new Cond(true, "className=" + quote(className), this::classMatches,
                    n -> "className=" + quote(n.getClassName())));
        }
        if (clickable != null) {
            boolean want = clickable;
            list.add(new Cond(false, "clickable=" + want, n -> n.isClickable() == want,
                    n -> "clickable=" + n.isClickable()));
        }
        if (enabled != null) {
            boolean want = enabled;
            list.add(new Cond(false, "enabled=" + want, n -> n.isEnabled() == want,
                    n -> "enabled=" + n.isEnabled()));
        }
        if (checked != null) {
            boolean want = checked;
            list.add(new Cond(false, "checked=" + want, n -> n.isChecked() == want,
                    n -> "checked=" + n.isChecked()));
        }
        if (selected != null) {
            boolean want = selected;
            list.add(new Cond(false, "selected=" + want, n -> n.isSelected() == want,
                    n -> "selected=" + n.isSelected()));
        }
        if (scrollable != null) {
            boolean want = scrollable;
            list.add(new Cond(false, "scrollable=" + want, n -> n.isScrollable() == want,
                    n -> "scrollable=" + n.isScrollable()));
        }
        if (visibleOnly) {
            list.add(new Cond(false, "visible=true", UiNode::isVisible, n -> "visible=" + n.isVisible()));
        }
        list = Collections.unmodifiableList(list);
        conds = list;
        return list;
    }

    private static String quote(String s) {
        return s == null ? "null" : '"' + s + '"';
    }

    private boolean idMatches(String viewId) {
        if (viewId == null) {
            return false;
        }
        if (id.contains(":id/")) {
            return id.equals(viewId);
        }
        return viewId.equals(id) || viewId.endsWith(":id/" + id);
    }

    private boolean classMatches(UiNode node) {
        if (className.indexOf('.') >= 0) {
            return className.equals(node.getClassName());
        }
        return className.equals(node.getSimpleClassName());
    }

    /**
     * 按窗口从上到下、窗口内先序遍历的顺序，返回第一个匹配的节点；没有时返回 null。
     */
    public UiNode findFirst(ScreenSnapshot screen) {
        for (UiNode node : screen.nodes()) {
            if (matches(node)) {
                return node;
            }
        }
        return null;
    }

    public List<UiNode> findAll(ScreenSnapshot screen) {
        List<UiNode> result = new ArrayList<>();
        for (UiNode node : screen.nodes()) {
            if (matches(node)) {
                result.add(node);
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Selector)) {
            return false;
        }
        Selector s = (Selector) o;
        return visibleOnly == s.visibleOnly
                && Objects.equals(text, s.text)
                && Objects.equals(textContains, s.textContains)
                && Objects.equals(textMatches, s.textMatches)
                && Objects.equals(desc, s.desc)
                && Objects.equals(descContains, s.descContains)
                && Objects.equals(descMatches, s.descMatches)
                && Objects.equals(id, s.id)
                && Objects.equals(className, s.className)
                && Objects.equals(clickable, s.clickable)
                && Objects.equals(enabled, s.enabled)
                && Objects.equals(checked, s.checked)
                && Objects.equals(selected, s.selected)
                && Objects.equals(scrollable, s.scrollable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, textContains, textMatches, desc, descContains, descMatches, id, className,
                clickable, enabled, checked, selected, scrollable, visibleOnly);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        append(sb, "text", text);
        append(sb, "textContains", textContains);
        append(sb, "textMatches", textMatches);
        append(sb, "desc", desc);
        append(sb, "descContains", descContains);
        append(sb, "descMatches", descMatches);
        append(sb, "id", id);
        append(sb, "className", className);
        append(sb, "clickable", clickable);
        append(sb, "enabled", enabled);
        append(sb, "checked", checked);
        append(sb, "selected", selected);
        append(sb, "scrollable", scrollable);
        if (!visibleOnly) {
            append(sb, "visible", false);
        }
        return sb.append('}').toString();
    }

    private static void append(StringBuilder sb, String key, Object value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 1) {
            sb.append(", ");
        }
        sb.append(key).append('=');
        if (value instanceof String) {
            sb.append('"').append(value).append('"');
        } else {
            sb.append(value);
        }
    }

    private static final class Cond {
        /** 文本、描述、id、类名这类决定“是哪个节点”的条件；其余是状态过滤。 */
        final boolean identity;
        final String label;
        final Predicate<UiNode> test;
        final Function<UiNode, String> actual;

        Cond(boolean identity, String label, Predicate<UiNode> test, Function<UiNode, String> actual) {
            this.identity = identity;
            this.label = label;
            this.test = test;
            this.actual = actual;
        }
    }
}

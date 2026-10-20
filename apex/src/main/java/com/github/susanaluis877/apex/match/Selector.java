package com.github.susanaluis877.apex.match;

import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        if (visibleOnly && !node.isVisible()) {
            return false;
        }
        if (text != null && !text.equals(node.getText())) {
            return false;
        }
        if (textContains != null && (node.getText() == null || !node.getText().contains(textContains))) {
            return false;
        }
        if (textPattern != null && (node.getText() == null || !textPattern.matcher(node.getText()).matches())) {
            return false;
        }
        if (desc != null && !desc.equals(node.getDesc())) {
            return false;
        }
        if (descContains != null && (node.getDesc() == null || !node.getDesc().contains(descContains))) {
            return false;
        }
        if (descPattern != null && (node.getDesc() == null || !descPattern.matcher(node.getDesc()).matches())) {
            return false;
        }
        if (id != null && !idMatches(node.getViewId())) {
            return false;
        }
        if (className != null && !classMatches(node)) {
            return false;
        }
        if (clickable != null && clickable != node.isClickable()) {
            return false;
        }
        if (enabled != null && enabled != node.isEnabled()) {
            return false;
        }
        if (checked != null && checked != node.isChecked()) {
            return false;
        }
        if (selected != null && selected != node.isSelected()) {
            return false;
        }
        return scrollable == null || scrollable == node.isScrollable();
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
}

package com.github.susanaluis877.apex.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 某一时刻界面节点的不可变快照，字段对应 {@code AccessibilityNodeInfo} 的常用属性。
 *
 * <p>节点只在产生它的那一轮快照内有效，不要跨轮缓存。
 */
public final class UiNode {

    private static final int CLICKABLE = 1;
    private static final int LONG_CLICKABLE = 1 << 1;
    private static final int ENABLED = 1 << 2;
    private static final int CHECKABLE = 1 << 3;
    private static final int CHECKED = 1 << 4;
    private static final int SELECTED = 1 << 5;
    private static final int SCROLLABLE = 1 << 6;
    private static final int EDITABLE = 1 << 7;
    private static final int FOCUSED = 1 << 8;
    private static final int VISIBLE = 1 << 9;

    private final int handle;
    private final String className;
    private final String text;
    private final String desc;
    private final String viewId;
    private final String packageName;
    private final Bounds bounds;
    private final int flags;
    private final List<UiNode> children;
    private UiNode parent;

    private UiNode(Builder b, List<UiNode> children) {
        this.handle = b.handle;
        this.className = b.className;
        this.text = b.text;
        this.desc = b.desc;
        this.viewId = b.viewId;
        this.packageName = b.packageName;
        this.bounds = b.bounds;
        this.flags = b.flags;
        this.children = children;
    }

    /**
     * 节点在所属快照内的编号，由快照的生产者分配，SDK 内部用它找回原始节点。
     */
    public int getHandle() {
        return handle;
    }

    public String getClassName() {
        return className;
    }

    /**
     * 类名最后一段，例如 {@code android.widget.Button} → {@code Button}。
     */
    public String getSimpleClassName() {
        if (className == null) {
            return null;
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    public String getText() {
        return text;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 完整资源 id，例如 {@code com.foo:id/submit}；没有 id 时为 null。
     */
    public String getViewId() {
        return viewId;
    }

    public String getPackageName() {
        return packageName;
    }

    public Bounds getBounds() {
        return bounds;
    }

    public boolean isClickable() {
        return (flags & CLICKABLE) != 0;
    }

    public boolean isLongClickable() {
        return (flags & LONG_CLICKABLE) != 0;
    }

    public boolean isEnabled() {
        return (flags & ENABLED) != 0;
    }

    public boolean isCheckable() {
        return (flags & CHECKABLE) != 0;
    }

    public boolean isChecked() {
        return (flags & CHECKED) != 0;
    }

    public boolean isSelected() {
        return (flags & SELECTED) != 0;
    }

    public boolean isScrollable() {
        return (flags & SCROLLABLE) != 0;
    }

    public boolean isEditable() {
        return (flags & EDITABLE) != 0;
    }

    public boolean isFocused() {
        return (flags & FOCUSED) != 0;
    }

    /**
     * 对用户可见（{@code AccessibilityNodeInfo.isVisibleToUser()}）。
     */
    public boolean isVisible() {
        return (flags & VISIBLE) != 0;
    }

    public UiNode getParent() {
        return parent;
    }

    public List<UiNode> getChildren() {
        return children;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getSimpleClassName() == null ? "?" : getSimpleClassName());
        if (viewId != null) {
            sb.append(" id=").append(viewId);
        }
        if (text != null) {
            sb.append(" text=\"").append(text).append('"');
        }
        if (desc != null) {
            sb.append(" desc=\"").append(desc).append('"');
        }
        sb.append(' ').append(bounds);
        return sb.toString();
    }

    public static final class Builder {

        private int handle = -1;
        private String className;
        private String text;
        private String desc;
        private String viewId;
        private String packageName;
        private Bounds bounds = Bounds.EMPTY;
        private int flags = ENABLED | VISIBLE;
        private final List<Builder> children = new ArrayList<>();

        public Builder handle(int handle) {
            this.handle = handle;
            return this;
        }

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder desc(String desc) {
            this.desc = desc;
            return this;
        }

        public Builder viewId(String viewId) {
            this.viewId = viewId;
            return this;
        }

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder bounds(Bounds bounds) {
            this.bounds = bounds != null ? bounds : Bounds.EMPTY;
            return this;
        }

        public Builder bounds(int left, int top, int right, int bottom) {
            return bounds(new Bounds(left, top, right, bottom));
        }

        public Builder clickable(boolean value) {
            return flag(CLICKABLE, value);
        }

        public Builder longClickable(boolean value) {
            return flag(LONG_CLICKABLE, value);
        }

        public Builder enabled(boolean value) {
            return flag(ENABLED, value);
        }

        public Builder checkable(boolean value) {
            return flag(CHECKABLE, value);
        }

        public Builder checked(boolean value) {
            return flag(CHECKED, value);
        }

        public Builder selected(boolean value) {
            return flag(SELECTED, value);
        }

        public Builder scrollable(boolean value) {
            return flag(SCROLLABLE, value);
        }

        public Builder editable(boolean value) {
            return flag(EDITABLE, value);
        }

        public Builder focused(boolean value) {
            return flag(FOCUSED, value);
        }

        public Builder visible(boolean value) {
            return flag(VISIBLE, value);
        }

        public Builder child(Builder child) {
            children.add(child);
            return this;
        }

        private Builder flag(int bit, boolean value) {
            flags = value ? flags | bit : flags & ~bit;
            return this;
        }

        /**
         * 递归构建整棵子树，并建立父子关系。
         */
        public UiNode build() {
            List<UiNode> built = new ArrayList<>(children.size());
            for (Builder child : children) {
                built.add(child.build());
            }
            UiNode node = new UiNode(this, Collections.unmodifiableList(built));
            for (UiNode child : built) {
                child.parent = node;
            }
            return node;
        }
    }
}

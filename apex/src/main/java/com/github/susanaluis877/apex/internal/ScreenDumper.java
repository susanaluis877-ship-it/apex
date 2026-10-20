package com.github.susanaluis877.apex.internal;

import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;
import com.github.susanaluis877.apex.node.UiWindow;

/**
 * 把快照打印成便于阅读的节点树，用来编写选择器。
 */
public final class ScreenDumper {

    private ScreenDumper() {
    }

    public static String dump(ScreenSnapshot screen) {
        StringBuilder sb = new StringBuilder();
        sb.append("screen seq=").append(screen.getSeq())
                .append(" pkg=").append(screen.getPackageName())
                .append(" activity=").append(screen.getActivity())
                .append(" size=").append(screen.getScreenWidth()).append('x').append(screen.getScreenHeight());
        if (screen.isPartial()) {
            sb.append(" (partial)");
        }
        sb.append('\n');
        for (int i = 0; i < screen.getWindows().size(); i++) {
            UiWindow window = screen.getWindows().get(i);
            sb.append("[window ").append(i).append("] layer=").append(window.getLayer());
            if (window.isActive()) {
                sb.append(" active");
            }
            if (window.getTitle() != null) {
                sb.append(" title=\"").append(window.getTitle()).append('"');
            }
            sb.append('\n');
            dump(window.getRoot(), 1, sb);
        }
        return sb.toString();
    }

    private static void dump(UiNode node, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(node.getSimpleClassName());
        if (node.getViewId() != null) {
            sb.append(" id=").append(node.getViewId());
        }
        if (node.getText() != null) {
            sb.append(" text=\"").append(node.getText()).append('"');
        }
        if (node.getDesc() != null) {
            sb.append(" desc=\"").append(node.getDesc()).append('"');
        }
        sb.append(' ').append(node.getBounds());
        if (node.isClickable()) {
            sb.append(" clickable");
        }
        if (node.isLongClickable()) {
            sb.append(" long-clickable");
        }
        if (node.isScrollable()) {
            sb.append(" scrollable");
        }
        if (node.isEditable()) {
            sb.append(" editable");
        }
        if (node.isCheckable()) {
            sb.append(node.isChecked() ? " checked" : " unchecked");
        }
        if (node.isSelected()) {
            sb.append(" selected");
        }
        if (!node.isEnabled()) {
            sb.append(" disabled");
        }
        if (!node.isVisible()) {
            sb.append(" invisible");
        }
        sb.append('\n');
        for (UiNode child : node.getChildren()) {
            dump(child, depth + 1, sb);
        }
    }
}

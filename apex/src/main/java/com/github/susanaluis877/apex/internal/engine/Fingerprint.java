package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import java.util.Objects;

/**
 * 屏幕内容指纹。只看可见节点的类名、id、文本、描述、位置、状态描述、取值范围和勾选/可用/选中状态，
 * 用来判断执行动作后页面是否有变化：没变化才重试，避免在加载中重复点击。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 包含不可见节点的指纹。滚轮选择器这类虚拟节点常被标成不可见，但它们的文本会随操作变化；
     * 判断“滚动后有没有变化”时用这个，判断“页面有没有变化”（是否重试）仍用 {@link #of}。
     */
    public static long ofAll(ScreenSnapshot screen) {
        long h = 1125899906842597L;
        h = 31 * h + Objects.hashCode(screen.getPackageName());
        for (UiNode n : screen.nodes()) {
            h = 31 * h + Objects.hashCode(n.getClassName());
            h = 31 * h + Objects.hashCode(n.getText());
            h = 31 * h + Objects.hashCode(n.getDesc());
            h = 31 * h + Objects.hashCode(n.getStateDescription());
            h = 31 * h + Objects.hashCode(n.getRange());
            h = 31 * h + n.getBounds().hashCode();
            h = 31 * h + (n.isChecked() ? 1 : 0) + (n.isSelected() ? 2 : 0) + (n.isVisible() ? 4 : 0);
        }
        return h;
    }

    /**
     * 只看内容、不看位置的指纹（含不可见节点）。判断“滚动后有没有新内容”时用它：
     * 列表滚到头时有些 ROM 会做回弹动画，节点位置还在变，但内容已经不变了。
     */
    public static long content(ScreenSnapshot screen) {
        long h = 1125899906842597L;
        h = 31 * h + Objects.hashCode(screen.getPackageName());
        for (UiNode n : screen.nodes()) {
            h = 31 * h + Objects.hashCode(n.getClassName());
            h = 31 * h + Objects.hashCode(n.getText());
            h = 31 * h + Objects.hashCode(n.getDesc());
            h = 31 * h + Objects.hashCode(n.getStateDescription());
            h = 31 * h + Objects.hashCode(n.getRange());
            h = 31 * h + (n.isChecked() ? 1 : 0) + (n.isSelected() ? 2 : 0) + (n.isVisible() ? 4 : 0);
        }
        return h;
    }

    public static long of(ScreenSnapshot screen) {
        long h = 1125899906842597L;
        h = 31 * h + Objects.hashCode(screen.getPackageName());
        h = 31 * h + Objects.hashCode(screen.getActivity());
        for (UiNode n : screen.nodes()) {
            if (!n.isVisible()) {
                continue;
            }
            h = 31 * h + Objects.hashCode(n.getClassName());
            h = 31 * h + Objects.hashCode(n.getViewId());
            h = 31 * h + Objects.hashCode(n.getText());
            h = 31 * h + Objects.hashCode(n.getDesc());
            h = 31 * h + n.getBounds().hashCode();
            h = 31 * h + Objects.hashCode(n.getStateDescription());
            h = 31 * h + Objects.hashCode(n.getRange());
            h = 31 * h + (n.isChecked() ? 1 : 0) + (n.isEnabled() ? 2 : 0) + (n.isSelected() ? 4 : 0);
        }
        return h;
    }
}

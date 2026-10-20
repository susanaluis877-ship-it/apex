package com.github.susanaluis877.apex.internal.engine;

import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;

import java.util.Objects;

/**
 * 屏幕内容指纹。只看可见节点的类名、id、文本、描述、位置和勾选/可用/选中状态，
 * 用来判断执行动作后页面是否有变化：没变化才重试，避免在加载中重复点击。
 */
public final class Fingerprint {

    private Fingerprint() {
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
            h = 31 * h + (n.isChecked() ? 1 : 0) + (n.isEnabled() ? 2 : 0) + (n.isSelected() ? 4 : 0);
        }
        return h;
    }
}

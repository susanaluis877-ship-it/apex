package com.github.susanaluis877.apex.testutil;

import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;
import com.github.susanaluis877.apex.node.UiWindow;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的节点树和快照构造工具。
 */
public final class Trees {

    public static final String PKG = "com.foo";
    public static final int WIDTH = 1080;
    public static final int HEIGHT = 2400;

    private static long seq;

    private Trees() {
    }

    public static UiNode.Builder frame(UiNode.Builder... children) {
        UiNode.Builder b = new UiNode.Builder().className("android.widget.FrameLayout")
                .packageName(PKG).bounds(0, 0, WIDTH, HEIGHT);
        for (UiNode.Builder child : children) {
            b.child(child);
        }
        return b;
    }

    public static UiNode.Builder text(String text) {
        return new UiNode.Builder().className("android.widget.TextView").packageName(PKG)
                .text(text).bounds(0, 0, 100, 50);
    }

    public static UiNode.Builder button(String id, String text) {
        return new UiNode.Builder().className("android.widget.Button").packageName(PKG)
                .viewId(PKG + ":id/" + id).text(text).clickable(true).bounds(100, 200, 300, 260);
    }

    /** 每个参数是一个窗口的根节点，排在前面的窗口在上层。 */
    public static ScreenSnapshot screen(String pkg, String activity, UiNode.Builder... windowRoots) {
        List<UiWindow> windows = new ArrayList<>();
        int layer = windowRoots.length;
        for (int i = 0; i < windowRoots.length; i++) {
            windows.add(new UiWindow(i, null, layer--, i == 0, windowRoots[i].build()));
        }
        return new ScreenSnapshot(++seq, pkg, activity, windows, WIDTH, HEIGHT, false);
    }

    public static ScreenSnapshot screen(UiNode.Builder... windowRoots) {
        return screen(PKG, PKG + ".MainActivity", windowRoots);
    }
}

package com.github.susanaluis877.apex.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 某一时刻整个屏幕的快照：应用窗口（从上到下）及其节点树，外加当前包名、Activity 和屏幕尺寸。
 */
public final class ScreenSnapshot {

    private final long seq;
    private final String packageName;
    private final String activity;
    private final List<UiWindow> windows;
    private final int screenWidth;
    private final int screenHeight;
    private final boolean partial;
    private List<UiNode> nodes;

    /**
     * @param seq          快照序号，单调递增，用来判断节点是否属于最新快照
     * @param packageName  最上层应用窗口所属的包名
     * @param activity     最近一次识别到的该包的 Activity 类名，可能为 null
     * @param windows      应用窗口，按 Z 序从上到下排列
     * @param partial      节点数、深度或耗时超限，节点树不完整
     */
    public ScreenSnapshot(long seq, String packageName, String activity, List<UiWindow> windows,
                          int screenWidth, int screenHeight, boolean partial) {
        this.seq = seq;
        this.packageName = packageName;
        this.activity = activity;
        this.windows = Collections.unmodifiableList(new ArrayList<>(windows));
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.partial = partial;
    }

    public long getSeq() {
        return seq;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getActivity() {
        return activity;
    }

    public List<UiWindow> getWindows() {
        return windows;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public boolean isPartial() {
        return partial;
    }

    /**
     * 所有节点，按窗口从上到下、窗口内先序遍历的顺序排列。查找时排在前面的节点优先。
     */
    public List<UiNode> nodes() {
        List<UiNode> result = nodes;
        if (result == null) {
            List<UiNode> list = new ArrayList<>();
            for (UiWindow window : windows) {
                collect(window.getRoot(), list);
            }
            result = Collections.unmodifiableList(list);
            nodes = result;
        }
        return result;
    }

    /**
     * 可见节点上的前若干条非空文本，用于日志和超时信息。
     */
    public List<String> topTexts(int max) {
        List<String> texts = new ArrayList<>();
        for (UiNode node : nodes()) {
            if (texts.size() >= max) {
                break;
            }
            if (!node.isVisible()) {
                continue;
            }
            String t = node.getText() != null && !node.getText().isEmpty() ? node.getText() : node.getDesc();
            if (t != null && !t.isEmpty()) {
                texts.add(t);
            }
        }
        return texts;
    }

    private static void collect(UiNode node, List<UiNode> out) {
        out.add(node);
        for (UiNode child : node.getChildren()) {
            collect(child, out);
        }
    }

    @Override
    public String toString() {
        return "Screen{seq=" + seq + ", pkg=" + packageName + ", activity=" + activity
                + ", windows=" + windows.size() + ", nodes=" + nodes().size()
                + (partial ? ", partial" : "") + "}";
    }
}

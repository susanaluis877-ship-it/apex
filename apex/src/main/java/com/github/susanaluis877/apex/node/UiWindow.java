package com.github.susanaluis877.apex.node;

/**
 * 屏幕上的一个窗口及其节点树。Activity、Dialog、PopupWindow 各自是独立的窗口。
 */
public final class UiWindow {

    private final int id;
    private final String title;
    private final int layer;
    private final boolean active;
    private final UiNode root;

    public UiWindow(int id, String title, int layer, boolean active, UiNode root) {
        if (root == null) {
            throw new IllegalArgumentException("root is null");
        }
        this.id = id;
        this.title = title;
        this.layer = layer;
        this.active = active;
        this.root = root;
    }

    public int getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    /**
     * Z 序，越大越靠上。
     */
    public int getLayer() {
        return layer;
    }

    /**
     * 是否是当前接收输入的窗口。
     */
    public boolean isActive() {
        return active;
    }

    public UiNode getRoot() {
        return root;
    }

    @Override
    public String toString() {
        return "Window{id=" + id + ", title=" + title + ", layer=" + layer + ", active=" + active + "}";
    }
}

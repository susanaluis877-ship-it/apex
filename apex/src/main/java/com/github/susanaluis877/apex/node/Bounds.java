package com.github.susanaluis877.apex.node;

/**
 * 节点在屏幕上的矩形区域，单位像素。不使用 {@code android.graphics.Rect}，以便在 JVM 上测试。
 */
public final class Bounds {

    public static final Bounds EMPTY = new Bounds(0, 0, 0, 0);

    private final int left;
    private final int top;
    private final int right;
    private final int bottom;

    public Bounds(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int getLeft() {
        return left;
    }

    public int getTop() {
        return top;
    }

    public int getRight() {
        return right;
    }

    public int getBottom() {
        return bottom;
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public int centerX() {
        return left + width() / 2;
    }

    public int centerY() {
        return top + height() / 2;
    }

    public boolean isEmpty() {
        return width() <= 0 || height() <= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Bounds)) {
            return false;
        }
        Bounds b = (Bounds) o;
        return left == b.left && top == b.top && right == b.right && bottom == b.bottom;
    }

    @Override
    public int hashCode() {
        int h = left;
        h = 31 * h + top;
        h = 31 * h + right;
        h = 31 * h + bottom;
        return h;
    }

    @Override
    public String toString() {
        return "[" + left + "," + top + "][" + right + "," + bottom + "]";
    }
}

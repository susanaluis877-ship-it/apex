package com.github.susanaluis877.apex.action;

/**
 * 屏幕坐标。可以是绝对像素，也可以是相对屏幕宽高的比例（0~1），比例在执行时按当时的屏幕尺寸换算。
 */
public final class Coord {

    private final double x;
    private final double y;
    private final boolean ratio;

    private Coord(double x, double y, boolean ratio) {
        this.x = x;
        this.y = y;
        this.ratio = ratio;
    }

    public static Coord px(int x, int y) {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("coordinates must be >= 0: " + x + "," + y);
        }
        return new Coord(x, y, false);
    }

    public static Coord ratio(double x, double y) {
        if (x < 0 || x > 1 || y < 0 || y > 1) {
            throw new IllegalArgumentException("ratio must be within [0, 1]: " + x + "," + y);
        }
        return new Coord(x, y, true);
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public boolean isRatio() {
        return ratio;
    }

    /** 换算成像素横坐标。 */
    public int resolveX(int screenWidth) {
        return ratio ? (int) Math.round(x * Math.max(0, screenWidth - 1)) : (int) x;
    }

    /** 换算成像素纵坐标。 */
    public int resolveY(int screenHeight) {
        return ratio ? (int) Math.round(y * Math.max(0, screenHeight - 1)) : (int) y;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Coord)) {
            return false;
        }
        Coord c = (Coord) o;
        return ratio == c.ratio && Double.compare(x, c.x) == 0 && Double.compare(y, c.y) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(x) * 31 + Double.hashCode(y) * 7 + (ratio ? 1 : 0);
    }

    @Override
    public String toString() {
        return ratio ? "(" + x + "w," + y + "h)" : "(" + (int) x + "," + (int) y + ")";
    }
}

package com.github.susanaluis877.apex.node;

import java.util.Locale;
import java.util.Objects;

/**
 * 滑块、进度条这类节点的取值范围，对应 {@code AccessibilityNodeInfo.RangeInfo}。
 */
public final class Range {

    public enum Type {
        INT,
        FLOAT,
        /** 取值是 0~100 的百分比。 */
        PERCENT
    }

    private final Type type;
    private final float min;
    private final float max;
    private final float current;

    public Range(Type type, float min, float max, float current) {
        this.type = type != null ? type : Type.FLOAT;
        this.min = min;
        this.max = max;
        this.current = current;
    }

    public Type getType() {
        return type;
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    public float getCurrent() {
        return current;
    }

    /** 当前值在范围内的比例，0~1；范围为空时返回 0。 */
    public float ratio() {
        return max > min ? Math.max(0f, Math.min(1f, (current - min) / (max - min))) : 0f;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Range)) {
            return false;
        }
        Range r = (Range) o;
        return type == r.type && Float.compare(min, r.min) == 0 && Float.compare(max, r.max) == 0
                && Float.compare(current, r.current) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, min, max, current);
    }

    /** 例如 {@code 45/0..100}、{@code 0.5/0..1}。 */
    @Override
    public String toString() {
        return format(current) + "/" + format(min) + ".." + format(max) + (type == Type.PERCENT ? "%" : "");
    }

    /** 整数不带小数点，其他保留两位小数。 */
    public static String format(float v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }
}

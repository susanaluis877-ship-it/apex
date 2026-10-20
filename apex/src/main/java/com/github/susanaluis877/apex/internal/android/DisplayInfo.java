package com.github.susanaluis877.apex.internal.android;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

/**
 * 屏幕真实尺寸（含状态栏、导航栏）。每次快照都重新读取，以适应旋转。
 */
final class DisplayInfo {

    private DisplayInfo() {
    }

    /** @return {宽, 高}，单位像素；读不到时为 {0, 0} */
    @SuppressWarnings("deprecation")
    static int[] realSize(Context context) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (display == null) {
            return new int[]{0, 0};
        }
        DisplayMetrics metrics = new DisplayMetrics();
        // getRealMetrics 在 API 31 起废弃，但 24~36 都可用，且不要求视觉 Context
        display.getRealMetrics(metrics);
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }
}

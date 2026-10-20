package com.github.susanaluis877.apex.internal.android;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 根据 TYPE_WINDOW_STATE_CHANGED 事件记录每个包最近一次显示的 Activity。
 *
 * <p>这个事件的类名也可能是 Dialog、PopupWindow、抽屉等 View 类，按类名规则过滤掉。
 * 只是启发式判断，单 Activity、Compose、Flutter 应用里它不区分页面。
 */
public final class ActivityTracker {

    private static final String[] NON_ACTIVITY_PREFIXES = {
            "android.widget.", "android.view.", "android.inputmethodservice.", "android.app.Dialog",
            "androidx.", "com.google.android.material."
    };

    private final Map<String, String> activities = new ConcurrentHashMap<>();

    public void onWindowStateChanged(CharSequence packageName, CharSequence className) {
        if (packageName == null || className == null) {
            return;
        }
        String cls = className.toString();
        if (looksLikeActivity(cls)) {
            activities.put(packageName.toString(), cls);
        }
    }

    public String activityOf(String packageName) {
        return activities.get(packageName);
    }

    static boolean looksLikeActivity(String cls) {
        if (cls.isEmpty() || cls.indexOf('.') < 0) {
            return false;
        }
        for (String prefix : NON_ACTIVITY_PREFIXES) {
            if (cls.startsWith(prefix)) {
                return false;
            }
        }
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        return !simple.contains("Dialog") && !simple.contains("Popup") && !simple.contains("Menu");
    }
}

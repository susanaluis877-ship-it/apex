package com.github.susanaluis877.apex.internal.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.github.susanaluis877.apex.internal.ApexLog;
import com.github.susanaluis877.apex.internal.engine.ScreenSource;
import com.github.susanaluis877.apex.node.Range;
import com.github.susanaluis877.apex.node.ScreenSnapshot;
import com.github.susanaluis877.apex.node.UiNode;
import com.github.susanaluis877.apex.node.UiWindow;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 把当前所有应用窗口的节点树拷贝成纯 Java 快照，并记住每个 {@link UiNode} 对应的原始节点，供执行动作时使用。
 *
 * <p>只能在引擎线程上使用。只保留最近一次快照的节点映射，旧快照里的节点会被视为过期。
 * 不调用 {@code recycle()}：API 33 以下不回收只是少用对象池，重复回收反而会抛异常。
 */
public final class AccessibilityScreenSource implements ScreenSource {

    static final int MAX_NODES = 3_000;
    static final int MAX_DEPTH = 64;
    static final long MAX_SNAPSHOT_MS = 3_000;

    private final Supplier<AccessibilityService> service;
    private final ActivityTracker activityTracker;
    private final Rect rect = new Rect();
    private long seq;
    private Map<UiNode, AccessibilityNodeInfo> handles = new IdentityHashMap<>();

    public AccessibilityScreenSource(Supplier<AccessibilityService> service, ActivityTracker activityTracker) {
        this.service = service;
        this.activityTracker = activityTracker;
    }

    @Override
    public ScreenSnapshot snapshot() {
        AccessibilityService svc = service.get();
        if (svc == null) {
            return null;
        }
        try {
            return capture(svc);
        } catch (RuntimeException e) {
            // 服务断开、节点 IPC 失败等，按这一轮拿不到屏幕处理
            ApexLog.e("snapshot failed", e);
            return null;
        }
    }

    /** 找回快照节点对应的原始节点；节点不属于最近一次快照时返回 null。 */
    AccessibilityNodeInfo resolve(UiNode node) {
        return handles.get(node);
    }

    private ScreenSnapshot capture(AccessibilityService svc) {
        Budget budget = new Budget(SystemClock.uptimeMillis());
        List<AccessibilityNodeInfo> infos = new ArrayList<>();
        List<UiWindow> windows = new ArrayList<>();

        List<AccessibilityWindowInfo> appWindows = new ArrayList<>();
        List<AccessibilityWindowInfo> all = svc.getWindows();
        if (all != null) {
            // 只看应用窗口，排除状态栏、导航栏、输入法、无障碍浮层，避免误匹配
            for (AccessibilityWindowInfo w : all) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    appWindows.add(w);
                }
            }
        }
        appWindows.sort((a, b) -> Integer.compare(b.getLayer(), a.getLayer()));
        for (AccessibilityWindowInfo w : appWindows) {
            if (budget.exceeded) {
                break;
            }
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) {
                continue;
            }
            UiNode tree = copy(root, 0, infos, budget).build();
            CharSequence title = w.getTitle();
            windows.add(new UiWindow(w.getId(), title != null ? title.toString() : null, w.getLayer(),
                    w.isActive(), tree));
        }
        if (windows.isEmpty()) {
            // 没有声明 flagRetrieveInteractiveWindows 或系统没返回窗口列表时，退回当前活动窗口
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root != null) {
                windows.add(new UiWindow(-1, null, 0, true, copy(root, 0, infos, budget).build()));
            }
        }

        Map<UiNode, AccessibilityNodeInfo> map = new IdentityHashMap<>(infos.size() * 2);
        for (UiWindow window : windows) {
            index(window.getRoot(), infos, map);
        }
        handles = map;

        String pkg = windows.isEmpty() ? null : windows.get(0).getRoot().getPackageName();
        int[] size = DisplayInfo.realSize(svc);
        return new ScreenSnapshot(++seq, pkg, pkg != null ? activityTracker.activityOf(pkg) : null, windows,
                size[0], size[1], budget.exceeded);
    }

    // isChecked() 在 API 36 废弃，替代的 getChecked() 只有 36 才有；isChecked() 在 24~36 都可用
    @SuppressWarnings("deprecation")
    private UiNode.Builder copy(AccessibilityNodeInfo info, int depth, List<AccessibilityNodeInfo> infos,
                                Budget budget) {
        int handle = infos.size();
        infos.add(info);
        budget.nodes++;
        info.getBoundsInScreen(rect);
        UiNode.Builder b = new UiNode.Builder()
                .handle(handle)
                .className(str(info.getClassName()))
                .text(str(info.getText()))
                .desc(str(info.getContentDescription()))
                .viewId(info.getViewIdResourceName())
                .packageName(str(info.getPackageName()))
                .bounds(rect.left, rect.top, rect.right, rect.bottom)
                .clickable(info.isClickable())
                .longClickable(info.isLongClickable())
                .enabled(info.isEnabled())
                .checkable(info.isCheckable())
                .checked(info.isChecked())
                .selected(info.isSelected())
                .scrollable(info.isScrollable())
                .editable(info.isEditable())
                .focused(info.isFocused())
                .visible(info.isVisibleToUser());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.hint(str(info.getHintText()));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.stateDescription(str(info.getStateDescription()));
        }
        AccessibilityNodeInfo.RangeInfo range = info.getRangeInfo();
        if (range != null) {
            b.range(new Range(rangeType(range.getType()), range.getMin(), range.getMax(), range.getCurrent()));
        }
        for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
            int id = action.getId();
            if (id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                b.canScrollForward(true);
            } else if (id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                b.canScrollBackward(true);
            } else if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
                b.canSetProgress(true);
            }
        }
        int count = info.getChildCount();
        if (count > 0 && depth >= MAX_DEPTH) {
            budget.exceeded = true;
            return b;
        }
        for (int i = 0; i < count; i++) {
            if (budget.check()) {
                break;
            }
            AccessibilityNodeInfo child = info.getChild(i);
            if (child != null) {
                b.child(copy(child, depth + 1, infos, budget));
            }
        }
        return b;
    }

    private static void index(UiNode node, List<AccessibilityNodeInfo> infos, Map<UiNode, AccessibilityNodeInfo> map) {
        map.put(node, infos.get(node.getHandle()));
        for (UiNode child : node.getChildren()) {
            index(child, infos, map);
        }
    }

    private static Range.Type rangeType(int type) {
        switch (type) {
            case AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT:
                return Range.Type.INT;
            case AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT:
                return Range.Type.PERCENT;
            default:
                return Range.Type.FLOAT;
        }
    }

    private static String str(CharSequence s) {
        return s != null ? s.toString() : null;
    }

    private static final class Budget {
        final long start;
        int nodes;
        boolean exceeded;

        Budget(long start) {
            this.start = start;
        }

        /** 超出节点数或耗时上限时返回 true，快照会被标记为不完整。 */
        boolean check() {
            if (!exceeded && (nodes >= MAX_NODES || SystemClock.uptimeMillis() - start > MAX_SNAPSHOT_MS)) {
                exceeded = true;
            }
            return exceeded;
        }
    }
}

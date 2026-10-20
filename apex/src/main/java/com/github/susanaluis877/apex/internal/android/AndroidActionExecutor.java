package com.github.susanaluis877.apex.internal.android;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import com.github.susanaluis877.apex.action.Action;
import com.github.susanaluis877.apex.internal.engine.ActionExecutor;
import com.github.susanaluis877.apex.internal.engine.ActionResult;
import com.github.susanaluis877.apex.node.UiNode;

import java.util.function.Supplier;

/**
 * 用无障碍服务执行设备操作。只能在引擎线程上使用。
 */
public final class AndroidActionExecutor implements ActionExecutor {

    private final AccessibilityScreenSource source;
    private final Supplier<AccessibilityService> service;

    public AndroidActionExecutor(AccessibilityScreenSource source, Supplier<AccessibilityService> service) {
        this.source = source;
        this.service = service;
    }

    @Override
    public ActionResult clickNode(UiNode node, boolean longClick) {
        return performOnNode(node, longClick
                ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                : AccessibilityNodeInfo.ACTION_CLICK, null);
    }

    @Override
    public ActionResult scrollNode(UiNode node, Action.Direction direction) {
        int action;
        switch (direction) {
            case FORWARD:
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                break;
            case BACKWARD:
                action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                break;
            case UP:
                action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
                break;
            case DOWN:
                action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
                break;
            case LEFT:
                action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
                break;
            default:
                action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
                break;
        }
        return performOnNode(node, action, null);
    }

    @Override
    public ActionResult setText(UiNode node, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return performOnNode(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    @Override
    public ActionResult gesture(int x1, int y1, int x2, int y2, long durationMs) {
        AccessibilityService svc = service.get();
        if (svc == null) {
            return ActionResult.soft("service unavailable");
        }
        return GestureDispatcher.dispatch(svc, x1, y1, x2, y2, durationMs);
    }

    @Override
    public ActionResult global(Action.GlobalAction kind) {
        AccessibilityService svc = service.get();
        if (svc == null) {
            return ActionResult.soft("service unavailable");
        }
        int action;
        switch (kind) {
            case BACK:
                action = AccessibilityService.GLOBAL_ACTION_BACK;
                break;
            case HOME:
                action = AccessibilityService.GLOBAL_ACTION_HOME;
                break;
            case RECENTS:
                action = AccessibilityService.GLOBAL_ACTION_RECENTS;
                break;
            default:
                action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
                break;
        }
        return svc.performGlobalAction(action)
                ? ActionResult.OK
                : ActionResult.soft("performGlobalAction(" + kind + ") returned false");
    }

    @Override
    public ActionResult launchApp(String packageName) {
        AccessibilityService svc = service.get();
        if (svc == null) {
            return ActionResult.soft("service unavailable");
        }
        Intent intent = svc.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            return ActionResult.hard("no launch intent for " + packageName + " (not installed, or on Android 11+"
                    + " the host manifest lacks <queries><package android:name=\"" + packageName + "\"/></queries>)");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            // 系统绑定的无障碍服务豁免后台启动 Activity 的限制
            svc.startActivity(intent);
            return ActionResult.OK;
        } catch (RuntimeException e) {
            return ActionResult.hard("failed to launch " + packageName + ": " + e);
        }
    }

    private ActionResult performOnNode(UiNode node, int action, Bundle args) {
        AccessibilityNodeInfo info = source.resolve(node);
        if (info == null) {
            return ActionResult.soft("stale node (not from the latest snapshot): " + node);
        }
        try {
            // 绕过缓存确认节点仍然存在
            if (!info.refresh()) {
                return ActionResult.soft("node no longer exists: " + node);
            }
            boolean ok = args != null ? info.performAction(action, args) : info.performAction(action);
            return ok ? ActionResult.OK : ActionResult.soft("performAction returned false on " + node);
        } catch (RuntimeException e) {
            return ActionResult.soft("performAction failed on " + node + ": " + e);
        }
    }
}

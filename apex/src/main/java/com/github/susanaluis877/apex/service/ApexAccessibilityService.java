package com.github.susanaluis877.apex.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import com.github.susanaluis877.apex.internal.ApexCore;

/**
 * SDK 的无障碍服务，随 AAR 合并进宿主 manifest，由用户在系统无障碍设置里开启。
 *
 * <p>服务本身不做自动化，只把自己交给 SDK 使用，并把事件转成“界面有变化”的信号。
 */
public final class ApexAccessibilityService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ApexCore.get().onServiceConnected(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        // 在主线程回调且事件对象会被回收，这里只取需要的字段，不做任何耗时操作
        ApexCore.get().onAccessibilityEvent(event.getEventType(), event.getPackageName(), event.getClassName());
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        ApexCore.get().onServiceDisconnected(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        ApexCore.get().onServiceDisconnected(this);
        super.onDestroy();
    }
}

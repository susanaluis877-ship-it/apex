package com.github.susanaluis877.apex.internal.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;

import com.github.susanaluis877.apex.internal.engine.ActionResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同步派发单指手势：在引擎线程上阻塞等待手势完成。
 */
final class GestureDispatcher {

    private static final long EXTRA_WAIT_MS = 2_000;
    private static final int PENDING = 0;
    private static final int COMPLETED = 1;
    private static final int CANCELLED = 2;

    private GestureDispatcher() {
    }

    static ActionResult dispatch(AccessibilityService svc, int x1, int y1, int x2, int y2, long durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) {
            path.lineTo(x2, y2);
        }
        GestureDescription gesture;
        try {
            gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build();
        } catch (IllegalArgumentException e) {
            return ActionResult.hard("invalid gesture: " + e.getMessage());
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger outcome = new AtomicInteger(PENDING);
        AccessibilityService.GestureResultCallback callback = new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription description) {
                outcome.set(COMPLETED);
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription description) {
                outcome.set(CANCELLED);
                latch.countDown();
            }
        };
        boolean accepted;
        try {
            // handler 传 null：回调在服务主线程执行。不能用引擎线程的 looper，引擎线程正阻塞在 latch 上
            accepted = svc.dispatchGesture(gesture, callback, null);
        } catch (RuntimeException e) {
            return ActionResult.soft("dispatchGesture failed: " + e);
        }
        if (!accepted) {
            return ActionResult.soft("gesture rejected by system");
        }
        try {
            if (!latch.await(durationMs + EXTRA_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return ActionResult.soft("gesture result timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.cancelled();
        }
        // 新手势或用户触摸会打断正在进行的手势
        return outcome.get() == COMPLETED ? ActionResult.OK : ActionResult.soft("gesture cancelled");
    }
}

package com.github.susanaluis877.apex.internal.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ActivityTrackerTest {

    @Test
    public void filtersNonActivityWindowClasses() {
        assertTrue(ActivityTracker.looksLikeActivity("com.foo.ui.HomeActivity"));
        assertTrue(ActivityTracker.looksLikeActivity("com.foo.MainActivity$Alias"));
        assertFalse(ActivityTracker.looksLikeActivity("android.widget.FrameLayout"));
        assertFalse(ActivityTracker.looksLikeActivity("android.app.AlertDialog"));
        assertFalse(ActivityTracker.looksLikeActivity("android.widget.PopupWindow$PopupDecorView"));
        assertFalse(ActivityTracker.looksLikeActivity("androidx.appcompat.app.AppCompatDialog"));
        assertFalse(ActivityTracker.looksLikeActivity("com.foo.ui.ShareDialog"));
        assertFalse(ActivityTracker.looksLikeActivity("com.foo.widget.MorePopup"));
        assertFalse(ActivityTracker.looksLikeActivity("Activity"));
    }

    @Test
    public void keepsLastActivityPerPackage() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.onWindowStateChanged("com.foo", "com.foo.HomeActivity");
        tracker.onWindowStateChanged("com.foo", "android.app.AlertDialog");
        tracker.onWindowStateChanged("com.bar", "com.bar.LoginActivity");
        assertEquals("com.foo.HomeActivity", tracker.activityOf("com.foo"));
        assertEquals("com.bar.LoginActivity", tracker.activityOf("com.bar"));
        assertNull(tracker.activityOf("com.baz"));
    }
}

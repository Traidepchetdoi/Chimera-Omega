package com.omega.host;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

public class OmegaMacroService extends AccessibilityService {
    public static OmegaMacroService instance;

    @Override
    public void onServiceConnected() {
        instance = this;
    }

    public void injectTap(int x, int y) {
        if (instance == null) return;
        Path path = new Path();
        path.moveTo(x, y);
        // Chạm nhanh 10ms
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 10);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
}
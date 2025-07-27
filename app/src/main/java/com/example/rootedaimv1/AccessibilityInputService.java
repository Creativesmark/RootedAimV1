package com.example.rootedaimv1;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class AccessibilityInputService extends AccessibilityService {
    private static final String TAG = "AccessibilityInputService";
    private static AccessibilityInputService instance;

    @Override
    public void onServiceConnected() {
        instance = this;
        Log.d(TAG, "Accessibility Service connected");
    }

    public static void performTap(int x, int y) {
        if (instance == null) {
            Log.e(TAG, "Accessibility Service not initialized");
            return;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 10);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke)
            .build();
        instance.dispatchGesture(gesture, null, null);
        Log.d(TAG, "Accessibility tap at: " + x + ", " + y);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
}

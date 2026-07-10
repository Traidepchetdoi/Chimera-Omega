package com.omega.host;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.accessibility.AccessibilityEvent;

public class OmegaMacroService extends AccessibilityService {
    public static OmegaMacroService instance;
    private Handler gestureHandler;
    
    // Tọa độ vùng trống an toàn (Góc trên bên phải - Không chạm nút bắn/joystick)
    private final float ORIGIN_X = 900; 
    private final float ORIGIN_Y = 500;
    
    private final float SENSITIVITY = 0.15f; // Độ nhạy của cú nhích
    private final float MAX_STEP = 20.0f;    // Giới hạn 1 cú nhích tối đa 20 pixel
    private final float DEADZONE = 12.0f;    // Vùng chết: lệch < 12 pixel thì ngưng (Chống rung khi đã dính đầu)
    
    private long lastSwipeTime = 0;

    @Override
    public void onServiceConnected() {
        instance = this;
        HandlerThread thread = new HandlerThread("OmegaMicroSwipe");
        thread.start();
        gestureHandler = new Handler(thread.getLooper());
    }

    public void injectMicroSwipe(float dx, float dy, boolean locked) {
        if (instance == null || gestureHandler == null) return;

        // 1. Chốt chặn chống rung (Deadzone)
        if (Math.abs(dx) < DEADZONE && Math.abs(dy) < DEADZONE) return;
        
        // Nếu mất dấu quá xa, không nhích mù
        if (!locked && (Math.abs(dx) > 500 || Math.abs(dy) > 800)) return;

        // Rate-Limiter: Giới hạn 50 cú nhích/giây (20ms/cú) để tránh làm nghẽn Input Dispatcher gây giật lag
        long now = System.currentTimeMillis();
        if (now - lastSwipeTime < 20) return; 
        lastSwipeTime = now;

        // 2. Tính toán vector nhích
        float swipeX = dx * SENSITIVITY;
        float swipeY = dy * SENSITIVITY;

        swipeX = Math.max(-MAX_STEP, Math.min(MAX_STEP, swipeX));
        swipeY = Math.max(-MAX_STEP, Math.min(MAX_STEP, swipeY));

        // 3. Bơm Touch Event vi mô
        gestureHandler.post(() -> {
            Path path = new Path();
            path.moveTo(ORIGIN_X, ORIGIN_Y);
            path.lineTo(ORIGIN_X + swipeX, ORIGIN_Y + swipeY);
            
            // Thời gian 10ms: Tạo cảm giác "dính" nam châm
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 10);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(gesture, null, null);
        });
    }

    public void injectTap(int x, int y) {
        if (instance == null || gestureHandler == null) return;
        gestureHandler.post(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 10);
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
}
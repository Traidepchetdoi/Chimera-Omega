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
    
    // Tọa độ vùng trống an toàn (Góc trên bên phải - Tránh nút bắn/joystick)
    private final float ORIGIN_X = 1000; 
    private final float ORIGIN_Y = 400;
    
    // [OMEGA STABILIZER] THÔNG SỐ KHÓA CHẾT & HỖ TRỢ NHÍCH SIÊU VI MÔ
    private final float SENSITIVITY = 0.08f;   // Biên độ cực nhỏ (Chống trượt đà / Overshoot)
    private final float MAX_STEP = 15.0f;      // Giới hạn 1 cú nhích tối đa 15 pixel
    private final float DEADZONE = 5.0f;       // Khóa chết: lệch < 5 pixel thì ngưng (Dính chặt như nam châm)
    private final long STROKE_DURATION = 5;    // 5ms: Tốc độ xung ổn định
    private final long RATE_LIMIT_MS = 8;      // 8ms: Tần suất 125Hz (Bơm nhích liên hoàn)
    
    private long lastSwipeTime = 0;

    @Override
    public void onServiceConnected() {
        instance = this;
        HandlerThread thread = new HandlerThread("OmegaStabilizer");
        thread.start();
        gestureHandler = new Handler(thread.getLooper());
    }

    public void injectMicroSwipe(float dx, float dy, boolean locked) {
        if (instance == null || gestureHandler == null) return;

        // 1. Chốt chặn Khóa Chết (Deadzone 5px)
        if (Math.abs(dx) < DEADZONE && Math.abs(dy) < DEADZONE) return;
        
        // Nếu mất dấu quá xa, không nhích mù
        if (!locked && (Math.abs(dx) > 600 || Math.abs(dy) > 1000)) return;

        // 2. Rate-Limiter: Bơm 125 cú nhích/giây
        long now = System.currentTimeMillis();
        if (now - lastSwipeTime < RATE_LIMIT_MS) return; 
        lastSwipeTime = now;

        // 3. TÍNH TOÁN VECTOR NHÍCH (SIÊU VI MÔ)
        float rawX = dx * SENSITIVITY;
        float rawY = dy * SENSITIVITY;

        // ÉP GIỚI HẠN (CLAMP)
        float clampedX = Math.max(-MAX_STEP, Math.min(MAX_STEP, rawX));
        float clampedY = Math.max(-MAX_STEP, Math.min(MAX_STEP, rawY));

        // 4. ĐÓNG GÓI BIẾN FINAL CHO LAMBDA
        final float swipeX = clampedX;
        final float swipeY = clampedY;

        // 5. BƠM XUNG ỔN ĐỊNH
        gestureHandler.post(() -> {
            Path path = new Path();
            path.moveTo(ORIGIN_X, ORIGIN_Y);
            path.lineTo(ORIGIN_X + swipeX, ORIGIN_Y + swipeY);
            
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, STROKE_DURATION);
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
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
    
    // Tọa độ vùng trống an toàn
    private final float ORIGIN_X = 1000; 
    private final float ORIGIN_Y = 400;
    
    // [OMEGA SUB-PIXEL] THÔNG SỐ NHÍCH DƯỚI ĐIỂM ẢNH (0.5PX GRANULARITY)
    private final float SENSITIVITY = 0.015f;  // Hệ số nhân cực tiểu (Tạo ra các vector 0.1px - 0.5px)
    private final float MAX_STEP = 1.0f;       // Chặn cứng trần 1.0 pixel (Không bao giờ nhảy cóc)
    private final float DEADZONE = 0.5f;       // Khóa chết ở nửa điểm ảnh (Dính chặt vào tâm hộp sọ)
    private final long STROKE_DURATION = 4;    // 4ms: Xung nhịp chuẩn cho Unity Engine
    private final long RATE_LIMIT_MS = 4;      // 4ms: Tần suất 250Hz (Bù đắp cho việc nhích quá nhỏ)
    
    private long lastSwipeTime = 0;

    @Override
    public void onServiceConnected() {
        instance = this;
        HandlerThread thread = new HandlerThread("OmegaSubPixel");
        thread.start();
        gestureHandler = new Handler(thread.getLooper());
    }

    public void injectMicroSwipe(float dx, float dy, boolean locked) {
        if (instance == null || gestureHandler == null) return;

        // 1. Chốt chặn Khóa Chết (Deadzone 0.5px)
        if (Math.abs(dx) < DEADZONE && Math.abs(dy) < DEADZONE) return;
        
        // Nếu mất dấu quá xa, không nhích mù
        if (!locked && (Math.abs(dx) > 600 || Math.abs(dy) > 1000)) return;

        // 2. Rate-Limiter: Ép xung 250Hz
        long now = System.currentTimeMillis();
        if (now - lastSwipeTime < RATE_LIMIT_MS) return; 
        lastSwipeTime = now;

        // 3. TÍNH TOÁN VECTOR NHÍCH (SUB-PIXEL)
        float rawX = dx * SENSITIVITY;
        float rawY = dy * SENSITIVITY;

        // ÉP GIỚI HẠN (CLAMP) - Tối đa 1 pixel
        float clampedX = Math.max(-MAX_STEP, Math.min(MAX_STEP, rawX));
        float clampedY = Math.max(-MAX_STEP, Math.min(MAX_STEP, rawY));

        // 4. ĐÓNG GÓI BIẾN FINAL CHO LAMBDA
        final float swipeX = clampedX;
        final float swipeY = clampedY;

        // 5. BƠM XUNG DƯỚI ĐIỂM ẢNH
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
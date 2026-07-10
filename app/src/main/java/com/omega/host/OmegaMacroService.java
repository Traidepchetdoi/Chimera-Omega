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
    // Với màn hình 1200x2664, vùng này an toàn tuyệt đối
    private final float ORIGIN_X = 1000; 
    private final float ORIGIN_Y = 400;
    
    private final float SENSITIVITY = 0.15f; // Độ nhạy của cú nhích
    private final float MAX_STEP = 25.0f;    // Giới hạn 1 cú nhích tối đa 25 pixel
    private final float DEADZONE = 15.0f;    // Vùng chết: lệch < 15 pixel thì ngưng (Chống rung khi đã dính đầu)
    
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
        if (!locked && (Math.abs(dx) > 600 || Math.abs(dy) > 1000)) return;

        // Rate-Limiter: Giới hạn 50 cú nhích/giây (20ms/cú) để tránh làm nghẽn Input Dispatcher
        long now = System.currentTimeMillis();
        if (now - lastSwipeTime < 20) return; 
        lastSwipeTime = now;

        // 2. TÍNH TOÁN VECTOR NHÍCH (TẦNG STACK)
        float rawX = dx * SENSITIVITY;
        float rawY = dy * SENSITIVITY;

        // ÉP GIỚI HẠN (CLAMP)
        float clampedX = Math.max(-MAX_STEP, Math.min(MAX_STEP, rawX));
        float clampedY = Math.max(-MAX_STEP, Math.min(MAX_STEP, rawY));

        // 3. ĐÓNG GÓI BIẾN FINAL CHO LAMBDA (TẦNG HEAP CAPTURE)
        // Đây là chốt chặn giải quyết lỗi "effectively final" của Javac
        final float swipeX = clampedX;
        final float swipeY = clampedY;

        // 4. BƠM TOUCH EVENT VI MÔ
        gestureHandler.post(() -> {
            Path path = new Path();
            path.moveTo(ORIGIN_X, ORIGIN_Y);
            path.lineTo(ORIGIN_X + swipeX, ORIGIN_Y + swipeY);
            
            // Thời gian 10ms: Tạo cảm giác "dính" nam châm, giả lập cơ tay người
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
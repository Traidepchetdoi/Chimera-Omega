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
    // Với màn hình 1200x2664, vùng này an toàn tuyệt đối
    private final float ORIGIN_X = 1000; 
    private final float ORIGIN_Y = 400;
    
    // [OMEGA OVERCLOCK] THÔNG SỐ ÉP XUNG CỰC ĐẠI
    private final float SENSITIVITY = 0.55f;   // Biên độ nhích (Tăng gấp 3.5 lần)
    private final float MAX_STEP = 80.0f;      // Giới hạn 1 cú nhích tối đa 80 pixel (Nhích nhiều)
    private final float DEADZONE = 3.0f;       // Khóa chết: lệch < 3 pixel thì ngưng (Bám sát từng điểm ảnh)
    private final long STROKE_DURATION = 1;    // 1ms: Tốc độ xung điện (Nhích nhanh)
    private final long RATE_LIMIT_MS = 8;      // 8ms: Giới hạn vật lý của InputDispatcher (125Hz)
    
    private long lastSwipeTime = 0;

    @Override
    public void onServiceConnected() {
        instance = this;
        HandlerThread thread = new HandlerThread("OmegaOverclock");
        thread.start();
        gestureHandler = new Handler(thread.getLooper());
    }

    public void injectMicroSwipe(float dx, float dy, boolean locked) {
        if (instance == null || gestureHandler == null) return;

        // 1. Chốt chặn Khóa Chết (Deadzone 3px)
        if (Math.abs(dx) < DEADZONE && Math.abs(dy) < DEADZONE) return;
        
        // Nếu mất dấu quá xa, không nhích mù
        if (!locked && (Math.abs(dx) > 600 || Math.abs(dy) > 1000)) return;

        // 2. Rate-Limiter: Ép xung lên 125Hz (8ms/cú)
        long now = System.currentTimeMillis();
        if (now - lastSwipeTime < RATE_LIMIT_MS) return; 
        lastSwipeTime = now;

        // 3. TÍNH TOÁN VECTOR NHÍCH (BIÊN ĐỘ LỚN)
        float rawX = dx * SENSITIVITY;
        float rawY = dy * SENSITIVITY;

        // ÉP GIỚI HẠN (CLAMP)
        float clampedX = Math.max(-MAX_STEP, Math.min(MAX_STEP, rawX));
        float clampedY = Math.max(-MAX_STEP, Math.min(MAX_STEP, rawY));

        // 4. ĐÓNG GÓI BIẾN FINAL CHO LAMBDA
        final float swipeX = clampedX;
        final float swipeY = clampedY;

        // 5. BƠM XUNG ĐIỆN (1MS TAP)
        gestureHandler.post(() -> {
            Path path = new Path();
            path.moveTo(ORIGIN_X, ORIGIN_Y);
            path.lineTo(ORIGIN_X + swipeX, ORIGIN_Y + swipeY);
            
            // STROKE_DURATION = 1ms: Tạo ra cú tap nhanh hơn tốc độ phản xạ của server game
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
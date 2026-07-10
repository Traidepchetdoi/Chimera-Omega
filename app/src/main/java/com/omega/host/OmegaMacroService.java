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
    
    private final float ORIGIN_X = 1000; 
    private final float ORIGIN_Y = 400;
    
    // [OMEGA ACCUMULATOR] BỘ TÍCH PHÂN DƯỚI ĐIỂM ẢNH
    private float accX = 0;
    private float accY = 0;
    
    // Ngưỡng vật lý tối thiểu để Unity Engine ghi nhận (0.4px)
    private final float SUB_PIXEL_THRESHOLD = 0.4f; 
    private final long STROKE_DURATION = 4;    // 4ms
    private final long RATE_LIMIT_MS = 4;      // 250Hz
    
    private long lastSwipeTime = 0;

    @Override
    public void onServiceConnected() {
        instance = this;
        HandlerThread thread = new HandlerThread("OmegaAccumulator");
        thread.start();
        gestureHandler = new Handler(thread.getLooper());
    }

    public void injectMicroSwipe(float forceX, float forceY, boolean locked) {
        if (instance == null || gestureHandler == null) return;

        // 1. TÍCH LŨY SAI SỐ 0.001PX (SUB-PIXEL ACCUMULATION)
        // Nhân lực PID với 0.015 để tạo ra các vi phân 0.001px
        accX += forceX * 0.015f; 
        accY += forceY * 0.015f;

        // Nếu không có mục tiêu, xả bộ nhớ đệm
        if (!locked) {
            accX = 0; accY = 0;
            return;
        }

        // 2. KIỂM TRA NGƯỠNG VẬT LÝ (UNITY TOUCHSLOP)
        if (Math.abs(accX) < SUB_PIXEL_THRESHOLD && Math.abs(accY) < SUB_PIXEL_THRESHOLD) {
            return; // Chưa đủ 0.4px, giữ lại trong biến nhớ (Khóa chết tuyệt đối)
        }

        // 3. RATE-LIMITER (250Hz)
        long now = System.currentTimeMillis();
        if (now - lastSwipeTime < RATE_LIMIT_MS) return; 
        lastSwipeTime = now;

        // 4. TRÍCH XUẤT GÓI NHÍCH 0.4PX & XẢ BỘ NHỚ ĐỆM
        final float swipeX = Math.signum(accX) * SUB_PIXEL_THRESHOLD;
        final float swipeY = Math.signum(accY) * SUB_PIXEL_THRESHOLD;
        
        accX -= swipeX; // Trừ lại phần đã nhích
        accY -= swipeY;

        // 5. BƠM XUNG
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
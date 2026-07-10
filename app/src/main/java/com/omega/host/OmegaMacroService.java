package com.omega.host;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.view.accessibility.AccessibilityEvent;

public class OmegaMacroService extends AccessibilityService {
    public static OmegaMacroService instance;
    
    private Handler gestureHandler;
    private final Path reusablePath = new Path(); 
    
    private final float ORIGIN_X = 1000; 
    private final float ORIGIN_Y = 400;
    
    // [OMEGA TOUCH-DAC] BỘ TÍCH PHÂN 64-BIT & ĐIỀU CHẾ XUNG
    private double accX = 0; // 64-bit Double: Lưu trữ hàng triệu lần nhích 0.0001px không sai số
    private double accY = 0;
    
    private final double MICRO_STEP = 0.0001;       // Độ phân giải toán học cực hạn
    private final double PHYSICAL_THRESHOLD = 0.15; // Ngưỡng vật lý tối thiểu Unity chấp nhận
    private final long STROKE_DURATION = 1;         // 1ms
    
    private long lastSwipeTimeNano = 0;
    
    // Mảng tái sử dụng để truyền Double qua Message (ZERO-GC 64-BIT BRIDGE)
    private final double[] reusablePayload = new double[2];

    private final Handler.Callback gestureCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            // Giải mã Double từ mảng tái sử dụng
            double[] payload = (double[]) msg.obj;
            float swipeX = (float) payload[0];
            float swipeY = (float) payload[1];
            
            reusablePath.rewind();
            reusablePath.moveTo(ORIGIN_X, ORIGIN_Y);
            reusablePath.lineTo(ORIGIN_X + swipeX, ORIGIN_Y + swipeY);
            
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(reusablePath, 0, STROKE_DURATION);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            
            try { dispatchGesture(gesture, null, null); } catch (Exception e) {}
            return true;
        }
    };

    @Override
    public void onServiceConnected() {
        instance = this;
        HandlerThread thread = new HandlerThread("OmegaTouchDAC");
        thread.start();
        Process.setThreadPriority(thread.getThreadId(), Process.THREAD_PRIORITY_URGENT_AUDIO);
        gestureHandler = new Handler(thread.getLooper(), gestureCallback);
    }

    public void injectMicroSwipe(double forceX, double forceY, boolean locked) {
        if (instance == null || gestureHandler == null) return;

        // 1. TÍCH LŨY TOÁN HỌC 64-BIT (0.0001px RESOLUTION)
        accX += forceX * MICRO_STEP; 
        accY += forceY * MICRO_STEP;

        if (!locked) {
            accX = 0; accY = 0;
            return;
        }

        // 2. KIỂM TRA NGƯỠNG VẬT LÝ (UNITY FLOOR)
        if (Math.abs(accX) < PHYSICAL_THRESHOLD && Math.abs(accY) < PHYSICAL_THRESHOLD) {
            return; // Chưa đủ 0.15px, tiếp tục tích lũy trong bóng tối
        }

        // 3. RATE-LIMITER 1000HZ
        long nowNano = System.nanoTime();
        if (nowNano - lastSwipeTimeNano < 1_000_000L) return; 
        lastSwipeTimeNano = nowNano;

        // 4. XẢ XUNG PWM (PULSE WIDTH MODULATION)
        // Rút gọn về đúng 0.15px (hoặc -0.15px) để lừa Unity Engine
        double swipeX = Math.signum(accX) * PHYSICAL_THRESHOLD;
        double swipeY = Math.signum(accY) * PHYSICAL_THRESHOLD;
        
        // Trừ lại phần đã xả (Giữ nguyên phần dư 0.0001px cho frame sau)
        accX -= swipeX; 
        accY -= swipeY;

        // 5. TRUYỀN TẢI ZERO-GC 64-BIT
        reusablePayload[0] = swipeX;
        reusablePayload[1] = swipeY;

        Message msg = gestureHandler.obtainMessage();
        msg.obj = reusablePayload; // Gắn mảng double vào Message (Không tạo object mới)
        msg.sendToTarget();
    }

    public void injectTap(int x, int y) {
        if (instance == null || gestureHandler == null) return;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 10);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
}
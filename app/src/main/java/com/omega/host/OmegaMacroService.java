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
    
    // [OMEGA MAGNETIC LOCK] BỘ TÍCH PHÂN KHÓA TÂM TỪ TÍNH
    private float accX = 0;
    private float accY = 0;
    private final float SUB_PIXEL_THRESHOLD = 0.4f; 
    private final long STROKE_DURATION = 1; // 1ms cho 1000Hz
    
    private long lastSwipeTimeNano = 0;

    private final Handler.Callback gestureCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            float swipeX = msg.arg1 / 10000.0f;
            float swipeY = msg.arg2 / 10000.0f;
            
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
        HandlerThread thread = new HandlerThread("OmegaMagneticLock");
        thread.start();
        // Ép luồng lên mức ưu tiên cao nhất của Nhân Linux
        Process.setThreadPriority(thread.getThreadId(), Process.THREAD_PRIORITY_URGENT_AUDIO);
        gestureHandler = new Handler(thread.getLooper(), gestureCallback);
    }

    public void injectMicroSwipe(float forceX, float forceY, boolean locked) {
        if (instance == null || gestureHandler == null) return;

        // 1. TÍCH LŨY LỰC KÉO TỪ TÍNH (PID FORCE)
        // Không quan tâm súng gì, không quan tâm đạn bay thế nào.
        // Chỉ quan tâm: Tâm đang cách đầu bao xa -> Kéo về đúng bằng đó.
        accX += forceX * 0.025f; 
        accY += forceY * 0.025f;

        if (!locked) {
            accX = 0; accY = 0;
            return;
        }

        // 2. KIỂM TRA NGƯỠNG VẬT LÝ (SUB-PIXEL)
        if (Math.abs(accX) < SUB_PIXEL_THRESHOLD && Math.abs(accY) < SUB_PIXEL_THRESHOLD) {
            return; 
        }

        // 3. RATE-LIMITER 1000HZ (NANO TIME)
        long nowNano = System.nanoTime();
        if (nowNano - lastSwipeTimeNano < 1_000_000L) return; 
        lastSwipeTimeNano = nowNano;

        // 4. TRÍCH XUẤT GÓI NHÍCH & XẢ BỘ NHỚ ĐỆM
        float swipeX = Math.signum(accX) * SUB_PIXEL_THRESHOLD;
        float swipeY = Math.signum(accY) * SUB_PIXEL_THRESHOLD;
        
        accX -= swipeX; 
        accY -= swipeY;

        // 5. ÉP KIỂU INT (ZERO-GC) & BƠM XUNG
        int intX = (int) (swipeX * 10000);
        int intY = (int) (swipeY * 10000);

        Message msg = gestureHandler.obtainMessage();
        msg.arg1 = intX;
        msg.arg2 = intY;
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
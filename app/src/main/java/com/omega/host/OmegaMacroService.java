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
    
    private final long STROKE_DURATION = 1; // 1ms
    private long lastSwipeTimeNano = 0;
    
    private final double[] reusablePayload = new double[2];

    private final Handler.Callback gestureCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            double[] payload = (double[]) msg.obj;
            float swipeX = (float) payload[0];
            float swipeY = (float) payload[1];
            
            reusablePath.rewind();
            reusablePath.moveTo(ORIGIN_X, ORIGIN_Y);
            reusablePath.lineTo(ORIGIN_X + swipeX, ORIGIN_Y + swipeY);
            
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(reusablePath, 0, STROKE_DURATION);
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
            return true;
        }
    };

    @Override
    public void onServiceConnected() {
        instance = this;
        HandlerThread thread = new HandlerThread("OmegaHyper");
        thread.start();
        Process.setThreadPriority(thread.getThreadId(), Process.THREAD_PRIORITY_URGENT_AUDIO);
        gestureHandler = new Handler(thread.getLooper(), gestureCallback);
    }

    // Giữ nguyên tên hàm để không phải sửa OpticalPhantomService
    public void injectMicroSwipe(double swipeX, double swipeY, boolean locked) {
        if (instance == null || gestureHandler == null) return;
        
        // Nếu đã khóa chết (sai số < 0.05px) thì ngưng bơm để tâm đứng yên tuyệt đối
        if (!locked || (Math.abs(swipeX) < 0.05 && Math.abs(swipeY) < 0.05)) return;

        // Rate limit 1000Hz (1ms)
        long nowNano = System.nanoTime();
        if (nowNano - lastSwipeTimeNano < 1_000_000L) return; 
        lastSwipeTimeNano = nowNano;

        reusablePayload[0] = swipeX;
        reusablePayload[1] = swipeY;

        Message msg = gestureHandler.obtainMessage();
        msg.obj = reusablePayload;
        msg.sendToTarget();
    }

    public void injectTap(int x, int y) {
        if (instance == null || gestureHandler == null) return;
        Path path = new Path();
        path.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 10)).build(), null, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
}
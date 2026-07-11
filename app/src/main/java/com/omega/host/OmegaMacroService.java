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
    
    // [OMEGA 128-BIT ACCUMULATOR]
    private double accX = 0; // Dùng double 64-bit để chứa vi phân 0.00001 mà không bị trôi
    private double accY = 0;
    
    private final double MICRO_STEP = 0.00001;       // Độ phân giải toán học cực hạn
    private final double PHYSICAL_THRESHOLD = 0.08;  // Ngưỡng vật lý tối thiểu (Unity vẫn nhận)
    private final long STROKE_DURATION = 1;         
    
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
        HandlerThread thread = new HandlerThread("Omega128Bit");
        thread.start();
        Process.setThreadPriority(thread.getThreadId(), Process.THREAD_PRIORITY_URGENT_AUDIO);
        gestureHandler = new Handler(thread.getLooper(), gestureCallback);
    }

    public void injectMicroSwipe(double forceX, double forceY, boolean locked) {
        if (instance == null || gestureHandler == null) return;

        // Tích lũy vi phân 0.00001px vào biến độc lập (Không bị Float Absorption)
        accX += forceX * MICRO_STEP; 
        accY += forceY * MICRO_STEP;

        if (!locked) { accX = 0; accY = 0; return; }

        if (Math.abs(accX) < PHYSICAL_THRESHOLD && Math.abs(accY) < PHYSICAL_THRESHOLD) return;

        long nowNano = System.nanoTime();
        if (nowNano - lastSwipeTimeNano < 1_000_000L) return; 
        lastSwipeTimeNano = nowNano;

        double swipeX = Math.signum(accX) * PHYSICAL_THRESHOLD;
        double swipeY = Math.signum(accY) * PHYSICAL_THRESHOLD;
        
        accX -= swipeX; 
        accY -= swipeY;

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
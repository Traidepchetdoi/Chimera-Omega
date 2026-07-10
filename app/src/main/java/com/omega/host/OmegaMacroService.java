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
    private final Path reusablePath = new Path(); // Tái sử dụng Path (Zero-GC)
    
    private final float ORIGIN_X = 1000; 
    private final float ORIGIN_Y = 400;
    
    // [OMEGA 1000HZ] BỘ TÍCH PHÂN & NGƯỠNG VẬT LÝ
    private float accX = 0;
    private float accY = 0;
    private final float SUB_PIXEL_THRESHOLD = 0.4f; 
    private final long STROKE_DURATION = 1; // 1ms: Xung nhịp khớp với 1000Hz Digitizer
    
    private long lastSwipeTimeNano = 0;

    // Callback xử lý Message (Zero-GC: Không tạo Lambda, không tạo Object mới)
    private final Handler.Callback gestureCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            // Giải mã Float từ Int (Nhân 10000 ở dưới, chia 10000 ở đây)
            float swipeX = msg.arg1 / 10000.0f;
            float swipeY = msg.arg2 / 10000.0f;
            
            // Tái sử dụng Path cũ, không gọi new Path()
            reusablePath.rewind();
            reusablePath.moveTo(ORIGIN_X, ORIGIN_Y);
            reusablePath.lineTo(ORIGIN_X + swipeX, ORIGIN_Y + swipeY);
            
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(reusablePath, 0, STROKE_DURATION);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            
            try {
                dispatchGesture(gesture, null, null);
            } catch (Exception e) {
                // Bẫy lỗi: Nuốt ngoại lệ nếu InputDispatcher bị quá tải 1000Hz
            }
            return true;
        }
    };

    @Override
    public void onServiceConnected() {
        instance = this;
        HandlerThread thread = new HandlerThread("Omega1000Hz");
        thread.start();
        
        // [KERNEL OVERRIDE] Ép luồng lên mức ưu tiên cao nhất của hệ thống
        Process.setThreadPriority(thread.getThreadId(), Process.THREAD_PRIORITY_URGENT_AUDIO);
        
        gestureHandler = new Handler(thread.getLooper(), gestureCallback);
    }

    public void injectMicroSwipe(float forceX, float forceY, boolean locked) {
        if (instance == null || gestureHandler == null) return;

        // 1. TÍCH LŨY SAI SỐ 0.001PX (SUB-PIXEL ACCUMULATION)
        accX += forceX * 0.015f; 
        accY += forceY * 0.015f;

        if (!locked) {
            accX = 0; accY = 0;
            return;
        }

        // 2. KIỂM TRA NGƯỠNG VẬT LÝ (UNITY TOUCHSLOP)
        if (Math.abs(accX) < SUB_PIXEL_THRESHOLD && Math.abs(accY) < SUB_PIXEL_THRESHOLD) {
            return; 
        }

        // 3. RATE-LIMITER 1000HZ (DÙNG NANO TIME ĐỂ CHÍNH XÁC TUYỆT ĐỐI)
        long nowNano = System.nanoTime();
        if (nowNano - lastSwipeTimeNano < 1_000_000L) { // 1.000.000 ns = 1 ms
            return; 
        }
        lastSwipeTimeNano = nowNano;

        // 4. TRÍCH XUẤT GÓI NHÍCH & XẢ BỘ NHỚ ĐỆM
        float swipeX = Math.signum(accX) * SUB_PIXEL_THRESHOLD;
        float swipeY = Math.signum(accY) * SUB_PIXEL_THRESHOLD;
        
        accX -= swipeX; 
        accY -= swipeY;

        // 5. ÉP KIỂU FLOAT SANG INT ĐỂ TRUYỀN QUA MESSAGE (ZERO-GC)
        int intX = (int) (swipeX * 10000);
        int intY = (int) (swipeY * 10000);

        // Lấy Message từ Pool của Android (Không tốn RAM)
        Message msg = gestureHandler.obtainMessage();
        msg.arg1 = intX;
        msg.arg2 = intY;
        msg.sendToTarget(); // Bắn vào luồng 1000Hz
    }

    public void injectTap(int x, int y) {
        if (instance == null || gestureHandler == null) return;
        // Tap vẫn dùng cách cũ vì tần suất thấp
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 10);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
}
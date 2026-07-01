package com.chimera.touch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.net.Socket;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TouchService extends AccessibilityService {
    private static final String TAG = "OmegaReactive";
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private FaceDetector mFaceDetector;
    private Handler mHandler = new Handler();
    
    private Socket mSocket;
    private OutputStream mOut;
    private InputStream mIn;
    private boolean isConnected = false;
    
    private AtomicBoolean isProcessing = new AtomicBoolean(false);
    private volatile boolean isGestureRunning = false; 
    private float SCALE_FACTOR = 4.0f; 

    private WindowManager mWindowManager;
    private View mHudView;
    private float hudX = 600, hudY = 1332, hudHo = 50, hudPitch = 0;
    private float screenCenterX = 600, screenCenterY = 1332;
    private boolean isTargetVisible = false;

    @Override
    public void onServiceConnected() {
        initFaceDetector(); 
        initReactiveHud(); 
        connectToBareMetalCore();
    }

    private void initReactiveHud() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "⚠️ CHƯA CÓ QUYỀN VẼ ĐÈ. HUD SẼ BỊ TẮT.");
            return;
        }

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        screenCenterX = metrics.widthPixels / 2.0f;
        screenCenterY = metrics.heightPixels / 2.0f;

        mHudView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (!isTargetVisible) return;

                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setStyle(Paint.Style.STROKE);
                
                // 🦴 TÍNH TỌA ĐỘ VỰC THẲM (NƠI ĐẠN SẼ TRÚNG ĐẦU)
                float safe_ho = Math.max(hudHo, 5.0f);
                float perspectiveOffset = (safe_ho * 0.85f) + (5000.0f / safe_ho);
                float pitchRad = hudPitch * 0.0174533f;
                float postureShift = (float)Math.sin(pitchRad) * safe_ho * 1.5f;
                
                float finalY = hudY + perspectiveOffset + postureShift;
                
                // 🎯 ĐO KHOẢNG CÁCH ĐẾN TÂM MÀN HÌNH (SUY LUẬN TRẠNG THÁI KHÓA)
                float dx = hudX - screenCenterX;
                float dy = finalY - screenCenterY;
                float distToCenter = (float)Math.sqrt(dx*dx + dy*dy);

                float baseRadius = 35.0f;
                float time = System.currentTimeMillis();
                float pulse = (float)Math.sin(time * 0.015) * 8.0f; // Nhịp co bóp

                if (distToCenter < 30.0f) {
                    // 🔴 ĐÃ KHÓA CHẶT (ĐỎ RỰC - CO BÓP - BẮN NGAY!)
                    paint.setColor(Color.RED);
                    paint.setStrokeWidth(6.0f);
                    paint.setAlpha(255);
                    canvas.drawCircle(hudX, finalY, baseRadius + pulse, paint);
                    
                    // Dấu X Tâm ngắm Tử thần
                    paint.setStrokeWidth(3.0f);
                    canvas.drawLine(hudX - 12, finalY - 12, hudX + 12, finalY + 12, paint);
                    canvas.drawLine(hudX - 12, finalY + 12, hudX + 12, finalY - 12, paint);
                    
                } else if (distToCenter < 150.0f) {
                    // 🟡 ĐANG KÉO (VÀNG - THU HẸP)
                    paint.setColor(Color.YELLOW);
                    paint.setStrokeWidth(4.0f);
                    paint.setAlpha(200);
                    float dynamicRadius = baseRadius + (distToCenter / 3.0f);
                    canvas.drawCircle(hudX, finalY, dynamicRadius, paint);
                    
                } else {
                    // ⚪ NGOÀI TẦM (XÁM MỜ)
                    paint.setColor(Color.GRAY);
                    paint.setStrokeWidth(2.0f);
                    paint.setAlpha(100);
                    canvas.drawCircle(hudX, finalY, baseRadius, paint);
                }
                
                // Vẽ lại liên tục để tạo hiệu ứng Pulsing
                if (isTargetVisible) invalidate();
            }
        };
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | 
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        
        try {
            mWindowManager.addView(mHudView, params);
            Log.i(TAG, "🎯 REACTIVE RETICLE DEPLOYED.");
        } catch (Exception e) {
            Log.e(TAG, "Lỗi thêm HUD: " + e.getMessage());
        }
    }

    private void connectToBareMetalCore() {
        new Thread(() -> {
            while (true) {
                try {
                    mSocket = new Socket("127.0.0.1", 8082);
                    mSocket.setTcpNoDelay(true);
                    mOut = mSocket.getOutputStream();
                    mIn = mSocket.getInputStream();
                    isConnected = true;
                    Log.i(TAG, "🔥 BARE-METAL LINK ESTABLISHED");
                    
                    byte[] buffer = new byte[16];
                    while (isConnected) {
                        int read = 0;
                        while (read < 16) {
                            int count = mIn.read(buffer, read, 16 - read);
                            if (count < 0) throw new Exception("EOF");
                            read += count;
                        }
                        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
                        float sx = bb.getFloat(0);
                        float sy = bb.getFloat(4);
                        float ex = bb.getFloat(8);
                        float ey = bb.getFloat(12);
                        dispatchSafeDrag(sx, sy, ex, ey);
                    }
                } catch (Exception e) { 
                    isConnected = false;
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "START_VISION".equals(intent.getAction())) {
            int code = intent.getIntExtra("CODE", -1);
            Intent data = intent.getParcelableExtra("DATA");
            if (code != -1 && data != null) startVision(code, data); 
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void initFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();
        mFaceDetector = FaceDetection.getClient(options);
    }

    private void startVision(int code, Intent data) {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mMediaProjection = mgr.getMediaProjection(code, data);
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(metrics);
        
        int capW = metrics.widthPixels / 4;
        int capH = metrics.heightPixels / 4;
        SCALE_FACTOR = 4.0f;

        mImageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("OmegaVision",
                capW, capH, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);

        mImageReader.setOnImageAvailableListener(reader -> {
            if (isProcessing.get()) {
                Image dropImage = reader.acquireLatestImage();
                if (dropImage != null) dropImage.close();
                return;
            }
            Image image = reader.acquireLatestImage();
            if (image != null) {
                isProcessing.set(true);
                processImage(image);
                image.close();
            }
        }, mHandler);
    }

    private void processImage(@NonNull Image image) {
        InputImage inputImage = InputImage.fromMediaImage(image, 0);
        Task<List<Face>> result = mFaceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    if (isConnected && !faces.isEmpty()) {
                        float imgCenterX = (image.getWidth() * SCALE_FACTOR) / 2.0f;
                        float imgCenterY = (image.getHeight() * SCALE_FACTOR) / 2.0f;
                        
                        Face bestTarget = null;
                        float minDistSq = Float.MAX_VALUE;
                        
                        for (Face face : faces) {
                            float fx = face.getBoundingBox().exactCenterX() * SCALE_FACTOR;
                            float fy = face.getBoundingBox().exactCenterY() * SCALE_FACTOR;
                            float dx = fx - imgCenterX;
                            float dy = fy - imgCenterY;
                            float distSq = dx*dx + dy*dy;
                            
                            if (distSq < minDistSq) {
                                minDistSq = distSq;
                                bestTarget = face;
                            }
                        }
                        
                        if (bestTarget != null) {
                            hudX = bestTarget.getBoundingBox().exactCenterX() * SCALE_FACTOR;
                            hudY = bestTarget.getBoundingBox().exactCenterY() * SCALE_FACTOR;
                            hudHo = bestTarget.getBoundingBox().height() * SCALE_FACTOR;
                            hudPitch = bestTarget.getHeadEulerAngleX();
                            isTargetVisible = true;
                            
                            ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                            bb.putFloat(hudX); bb.putFloat(hudY); bb.putFloat(hudHo); bb.putFloat(hudPitch);
                            try { mOut.write(bb.array()); } catch (Exception e) {}
                        }
                    } else {
                        isTargetVisible = false;
                    }
                })
                .addOnCompleteListener(task -> isProcessing.set(false));
    }

    public void dispatchSafeDrag(float sx, float sy, float ex, float ey) {
        if (isGestureRunning) return; 
        isGestureRunning = true;

        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);
        
        GestureDescription.StrokeDescription stroke = 
            new GestureDescription.StrokeDescription(path, 0, 50);
            
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) { isGestureRunning = false; }
            @Override public void onCancelled(GestureDescription gestureDescription) { isGestureRunning = false; }
        }, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        super.onDestroy();
        isConnected = false;
        if (mHudView != null && mWindowManager != null) {
            try { mWindowManager.removeView(mHudView); } catch (Exception e) {}
        }
        if (mVirtualDisplay != null) mVirtualDisplay.release();
        if (mMediaProjection != null) mMediaProjection.stop();
        try { if (mSocket != null) mSocket.close(); } catch (Exception e) {}
    }
}

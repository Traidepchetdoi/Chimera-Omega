package com.omega.host;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

public class HostAccessibility extends AccessibilityService {
    private static HostAccessibility instance;
    private MediaProjection mProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private FaceDetector mFaceDetector;
    private Handler mHandler = new Handler();
    private boolean isProcessing = false;
    
    private float SCALE_FACTOR = 6.0f;
    private float screenCX, screenCY, screenW, screenH;
    private boolean userTouching = false;
    
    // 🧠 KALMAN FILTER STATE (Thay thế EMA)
    private float kX = 0, kY = 0;        // State estimate
    private float kVx = 0, kVy = 0;      // Velocity estimate
    private float kPx = 1, kPy = 1;      // Estimate error covariance
    private float kPvx = 1, kPvy = 1;
    private long lastFrameTime = 0;
    private boolean hasTarget = false;
    
    // 🎯 MICRO-CORRECTION STATE (Vi chỉnh trong deadzone)
    private float microOffsetX = 0, microOffsetY = 0;
    private int microCounter = 0;
    
    // ⚙️ QUANTUM PIN PARAMETERS
    private static final float DEADZONE = 40.0f;    // Giảm từ 80 xuống 40 để vi chỉnh
    private static final float PIN_ZONE = 150.0f;   // Vùng Infinity Pin mở rộng
    private static final float Q_PROCESS = 0.01f;   // Process noise Kalman
    private static final float Q_VELOCITY = 0.5f;   // Velocity noise
    private static final float R_MEASURE = 2.0f;    // Measurement noise (ML Kit jitter)
    private static final float JITTER_THRESHOLD = 3.0f; // Bỏ qua dao động <3px
    private static final float MAX_STEP_FAR = 80.0f;
    private static final float MAX_STEP_NEAR = 8.0f;
    private static final float MICRO_STEP = 1.5f;   // Vi chỉnh 1-2px trong deadzone

    @Override
    public void onServiceConnected() {
        instance = this;
        DisplayMetrics m = getResources().getDisplayMetrics();
        screenW = m.widthPixels; screenH = m.heightPixels;
        screenCX = screenW / 2f; screenCY = screenH / 2f;
        
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.1f)
                .build();
        mFaceDetector = FaceDetection.getClient(options);
    }

    public static void startCapture(int code, Intent data) {
        if (instance != null) instance._startCapture(code, data);
    }

    private void _startCapture(int code, Intent data) {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mProjection = mgr.getMediaProjection(code, data);
        int cW = (int)(screenW / SCALE_FACTOR);
        int cH = (int)(screenH / SCALE_FACTOR);
        mImageReader = ImageReader.newInstance(cW, cH, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mProjection.createVirtualDisplay("Omega", cW, cH,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);

        mImageReader.setOnImageAvailableListener(ir -> {
            Image img = ir.acquireLatestImage();
            if (img != null && !isProcessing) {
                isProcessing = true;
                processImage(img);
            } else if (img != null) {
                img.close();
            }
        }, mHandler);
    }

    private void processImage(@NonNull Image image) {
        InputImage input = InputImage.fromMediaImage(image, 0);
        mFaceDetector.process(input)
            .addOnSuccessListener(faces -> {
                try {
                    float bestX = -1, bestY = -1;
                    float minDistSq = Float.MAX_VALUE;
                    float bestSize = 0;

                    if (!faces.isEmpty()) {
                        for (Face face : faces) {
                            Rect box = face.getBoundingBox();
                            float fx = box.exactCenterX() * SCALE_FACTOR;
                            float fy = (box.top + (box.height() * 0.25f)) * SCALE_FACTOR;
                            float size = box.width() * box.height();
                            
                            // Ưu tiên mặt gần tâm + kích thước lớn
                            float distSq = (fx - screenCX)*(fx - screenCX) + (fy - screenCY)*(fy - screenCY);
                            float score = distSq - size * 10; // Score = khoảng cách - diện tích*10
                            
                            if (score < minDistSq) {
                                minDistSq = score;
                                bestX = fx;
                                bestY = fy;
                                bestSize = size;
                            }
                        }
                    }

                    if (bestX > 0) {
                        long now = System.nanoTime();
                        float dt = lastFrameTime == 0 ? 16.0f : (now - lastFrameTime) / 1_000_000f;
                        if (dt > 100) dt = 16.0f;
                        lastFrameTime = now;
                        
                        // 🧠 KALMAN FILTER UPDATE
                        if (!hasTarget) {
                            kX = bestX; kY = bestY;
                            kVx = 0; kVy = 0;
                            kPx = 1; kPy = 1;
                            kPvx = 1; kPvy = 1;
                            hasTarget = true;
                        } else {
                            // PREDICT
                            float predX = kX + kVx * dt;
                            float predY = kY + kVy * dt;
                            float predPx = kPx + dt*dt*kPvx + Q_PROCESS;
                            float predPy = kPy + dt*dt*kPvy + Q_PROCESS;
                            
                            // UPDATE
                            float zX = bestX, zY = bestY;
                            float yX = zX - predX;
                            float yY = zY - predY;
                            
                            // JITTER REJECTION: Bỏ qua nếu measurement nhảy <3px
                            float jitterDist = (float)Math.sqrt(yX*yX + yY*yY);
                            if (jitterDist < JITTER_THRESHOLD && jitterDist > 0.1f) {
                                // Giữ nguyên estimate, không update
                            } else {
                                float sX = predPx + R_MEASURE;
                                float sY = predPy + R_MEASURE;
                                float kGainX = predPx / sX;
                                float kGainY = predPy / sY;
                                
                                kX = predX + kGainX * yX;
                                kY = predY + kGainY * yY;
                                kPx = (1 - kGainX) * predPx;
                                kPy = (1 - kGainY) * predPy;
                                
                                // Velocity estimate
                                kVx = (kX - (kX - kVx*dt)) / dt;
                                kVy = (kY - (kY - kVy*dt)) / dt;
                                kPvx = Q_VELOCITY;
                                kPvy = Q_VELOCITY;
                            }
                        }

                        // 🔮 ADAPTIVE PREDICTION: Dự báo 2 frame phía trước (32ms)
                        float predictX = kX + kVx * 32.0f;
                        float predictY = kY + kVy * 32.0f;
                        
                        // Clamp prediction trong màn hình
                        predictX = Math.max(0, Math.min(screenW, predictX));
                        predictY = Math.max(0, Math.min(screenH, predictY));

                        float errX = predictX - screenCX;
                        float errY = predictY - screenCY;
                        float dist = (float)Math.sqrt(errX*errX + errY*errY);

                        // 🚫 ZERO RESISTANCE: Không vuốt khi user đang chạm
                        if (userTouching) {
                            // Reset micro-correction khi user can thiệp
                            microOffsetX = 0;
                            microOffsetY = 0;
                            microCounter = 0;
                        } else if (dist < DEADZONE) {
                            // 🎯 MICRO-CORRECTION: Vi chỉnh trong deadzone để "đóng đinh"
                            microCounter++;
                            if (microCounter % 3 == 0) { // Mỗi 3 frame vi chỉnh 1 lần
                                float microErrX = kX - screenCX;
                                float microErrY = kY - screenCY;
                                float microDist = (float)Math.sqrt(microErrX*microErrX + microErrY*microErrY);
                                
                                if (microDist > 2.0f && microDist < DEADZONE) {
                                    // Vi chỉnh 1.5px hướng về đầu địch
                                    float mx = (microErrX / microDist) * MICRO_STEP;
                                    float my = (microErrY / microDist) * MICRO_STEP;
                                    doSwipe(screenCX, screenCY, screenCX + mx, screenCY + my, 8);
                                }
                            }
                        } else if (dist < PIN_ZONE) {
                            // ⚡ INFINITY PIN: Dynamic step - gần = vi chỉnh, xa = kéo mạnh
                            float t = (dist - DEADZONE) / (PIN_ZONE - DEADZONE); // 0.0 → 1.0
                            float maxStep = MAX_STEP_NEAR + (MAX_STEP_FAR - MAX_STEP_NEAR) * t;
                            
                            float stepX = errX;
                            float stepY = errY;
                            float stepMag = (float)Math.sqrt(stepX*stepX + stepY*stepY);
                            if (stepMag > maxStep) {
                                float ratio = maxStep / stepMag;
                                stepX *= ratio;
                                stepY *= ratio;
                            }
                            
                            float targetX = screenCX + stepX;
                            float targetY = screenCY + stepY;
                            
                            // Duration tỉ lệ với khoảng cách (gần = nhanh, xa = chậm hơn)
                            int dur = (int)(8 + t * 8); // 8ms → 16ms
                            doSwipe(screenCX, screenCY, targetX, targetY, dur);
                            microCounter = 0;
                        } else {
                            // 🧲 FAR TRACKING: Kéo nhanh khi ở rất xa
                            float stepX = errX * 0.95f; // 95% để tránh overshoot
                            float stepY = errY * 0.95f;
                            
                            float stepMag = (float)Math.sqrt(stepX*stepX + stepY*stepY);
                            if (stepMag > MAX_STEP_FAR) {
                                float ratio = MAX_STEP_FAR / stepMag;
                                stepX *= ratio;
                                stepY *= ratio;
                            }
                            
                            doSwipe(screenCX, screenCY, screenCX + stepX, screenCY + stepY, 16);
                            microCounter = 0;
                        }
                    } else {
                        hasTarget = false;
                        microCounter = 0;
                    }
                } catch (Exception e) {
                    // Silent fail
                }
                
                isProcessing = false;
                image.close();
            })
            .addOnFailureListener(e -> {
                isProcessing = false;
                image.close();
            });
    }

    private void doSwipe(float cx, float cy, float tx, float ty, int dur) {
        try {
            Path p = new Path();
            p.moveTo(cx, cy);
            p.lineTo(tx, ty);
            GestureDescription.StrokeDescription s =
                new GestureDescription.StrokeDescription(p, 0, Math.max(8, dur));
            dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
        } catch (Exception e) { /* Silent */ }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            userTouching = true;
        } else if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            userTouching = false;
        }
    }
    
    @Override public void onInterrupt() {}
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mVirtualDisplay != null) mVirtualDisplay.release();
        if (mProjection != null) mProjection.stop();
        if (mFaceDetector != null) mFaceDetector.close();
    }
}
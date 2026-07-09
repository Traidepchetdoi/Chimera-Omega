package com.omega.host;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
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
    
    private float SCALE_FACTOR = 4.0f;
    private float screenCX, screenCY, screenW, screenH;
    private boolean userTouching = false;
    
    // 🎯 VÙNG CHẾT TOÀN PHẦN: 80px (Bao trùm toàn bộ đầu địch)
    private static final float DEADZONE = 80.0f;

    @Override
    public void onServiceConnected() {
        instance = this;
        DisplayMetrics m = getResources().getDisplayMetrics();
        screenW = m.widthPixels;
        screenH = m.heightPixels;
        screenCX = screenW / 2f;
        screenCY = screenH / 2f;
        
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
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
                float bestX = -1, bestY = -1;
                float minDistSq = Float.MAX_VALUE;

                if (!faces.isEmpty()) {
                    for (Face face : faces) {
                        android.graphics.RectF box = face.getBoundingBox();
                        float fx = box.exactCenterX() * SCALE_FACTOR;
                        // 🔑 KHÓA NGAY VÙNG MẮT/TRÁN: top + 25% height
                        float fy = box.top * SCALE_FACTOR + (box.height() * SCALE_FACTOR * 0.25f);
                        float distSq = (fx - screenCX)*(fx - screenCX) + (fy - screenCY)*(fy - screenCY);
                        if (distSq < minDistSq) {
                            minDistSq = distSq;
                            bestX = fx;
                            bestY = fy;
                        }
                    }
                }

                if (bestX > 0) {
                    float errX = bestX - screenCX;
                    float errY = bestY - screenCY;
                    float dist = (float)Math.sqrt(errX*errX + errY*errY);

                    // 🚫 TUYỆT ĐỐI KHÔNG VUỐT KHI:
                    // 1. User đang chạm màn hình (Zero Resistance)
                    // 2. Tâm đã trong vùng 80px quanh đầu địch (Target Freeze)
                    if (userTouching || dist < DEADZONE) {
                        isProcessing = false;
                        image.close();
                        return;
                    }

                    // ✅ Chỉ vuốt khi: Buông tay + Tâm ở xa đầu địch
                    Path path = new Path();
                    path.moveTo(screenCX, screenCY);
                    path.lineTo(bestX, bestY);
                    GestureDescription.StrokeDescription stroke =
                            new GestureDescription.StrokeDescription(path, 0, 12);
                    dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
                }
                isProcessing = false;
                image.close();
            })
            .addOnCompleteListener(task -> {});
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
    @Override public void onDestroy() {
        super.onDestroy();
        if (mVirtualDisplay != null) mVirtualDisplay.release();
        if (mProjection != null) mProjection.stop();
    }
}

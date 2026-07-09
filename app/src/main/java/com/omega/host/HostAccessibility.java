package com.omega.host;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
    
    // 🧠 TCP CLIENT: Kết nối tới Bộ Não C++
    private Socket socket;
    private OutputStream outStream;
    private InputStream inStream;
    private boolean tcpConnected = false;

    @Override
    public void onServiceConnected() {
        instance = this;
        DisplayMetrics m = getResources().getDisplayMetrics();
        screenW = m.widthPixels; screenH = m.heightPixels;
        screenCX = screenW / 2f; screenCY = screenH / 2f;
        
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build();
        mFaceDetector = FaceDetection.getClient(options);
        
        // 🚀 Khởi động Luồng Giao Tiếp Với C++
        new Thread(() -> {
            while (true) {
                try {
                    socket = new Socket("127.0.0.1", 8082);
                    outStream = socket.getOutputStream();
                    inStream = socket.getInputStream();
                    tcpConnected = true;
                    byte[] inBuffer = new byte[32];
                    while (tcpConnected) {
                        int read = inStream.read(inBuffer);
                        if (read == 32) {
                            ByteBuffer bb = ByteBuffer.wrap(inBuffer).order(ByteOrder.LITTLE_ENDIAN);
                            float tX = bb.getFloat(); float tY = bb.getFloat();
                            float c1x = bb.getFloat(); float c1y = bb.getFloat();
                            float c2x = bb.getFloat(); float c2y = bb.getFloat();
                            float dur = bb.getFloat(); float vis = bb.getFloat();
                            if (vis > 0.5f) doSwipe(c1x, c1y, c2x, c2y, (int)dur);
                        }
                    }
                } catch (Exception e) {
                    tcpConnected = false;
                    try { Thread.sleep(1000); } catch (Exception ex) {}
                }
            }
        }).start();
    }

    public static void startCapture(int code, Intent data) {
        if (instance != null) instance._startCapture(code, data);
    }

    private void _startCapture(int code, Intent data) {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mProjection = mgr.getMediaProjection(code, data);
        int cW = (int)(screenW / SCALE_FACTOR); int cH = (int)(screenH / SCALE_FACTOR);
        mImageReader = ImageReader.newInstance(cW, cH, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mProjection.createVirtualDisplay("Omega", cW, cH,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);

        mImageReader.setOnImageAvailableListener(ir -> {
            Image img = ir.acquireLatestImage();
            if (img != null && !isProcessing) { isProcessing = true; processImage(img); } 
            else if (img != null) img.close();
        }, mHandler);
    }

    private void sendToCpp(float hX, float hY, boolean found) {
        if (!tcpConnected || outStream == null) return;
        try {
            ByteBuffer bb = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            bb.putFloat(hX); bb.putFloat(hY);
            bb.putFloat(screenCX); bb.putFloat(screenCY);
            bb.putFloat(userTouching ? 1.0f : 0.0f);
            bb.putFloat(found ? 1.0f : 0.0f);
            bb.putFloat(0); bb.putFloat(0);
            outStream.write(bb.array());
        } catch (Exception e) {}
    }

    private void processImage(@NonNull Image image) {
        InputImage input = InputImage.fromMediaImage(image, 0);
        mFaceDetector.process(input)
            .addOnSuccessListener(faces -> {
                float bestX = -1, bestY = -1;
                float minDistSq = Float.MAX_VALUE;
                if (!faces.isEmpty()) {
                    for (Face face : faces) {
                        android.graphics.Rect box = face.getBoundingBox();
                        float fx = box.exactCenterX() * SCALE_FACTOR;
                        float fy = (box.top + (box.height() * 0.25f)) * SCALE_FACTOR;
                        float distSq = (fx - screenCX)*(fx - screenCX) + (fy - screenCY)*(fy - screenCY);
                        if (distSq < minDistSq) { minDistSq = distSq; bestX = fx; bestY = fy; }
                    }
                }
                // 📡 Gửi Tọa Độ Thô Xuống Bộ Não C++
                if (bestX > 0) sendToCpp(bestX, bestY, true);
                else sendToCpp(0, 0, false);
                
                isProcessing = false; image.close();
            }).addOnCompleteListener(task -> {});
    }

    private void doSwipe(float cx, float cy, float tx, float ty, int dur) {
        Path p = new Path(); p.moveTo(cx, cy); p.lineTo(tx, ty);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, Math.max(8, dur));
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) userTouching = true;
        else if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) userTouching = false;
    }
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        super.onDestroy();
        if (mVirtualDisplay != null) mVirtualDisplay.release();
        if (mProjection != null) mProjection.stop();
    }
}
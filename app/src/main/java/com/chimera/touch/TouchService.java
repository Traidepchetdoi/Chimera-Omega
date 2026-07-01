package com.chimera.touch;

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
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TouchService extends AccessibilityService {
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
    private long lastFrameTime = 0;
    
    // 🛡️ BIẾN TOÀN CỤC (Đặt tên riêng biệt 'm' để tránh mọi xung đột biến cục bộ)
    private float mLastEndX = -1f; 
    private float mLastEndY = -1f; 

    @Override
    public void onServiceConnected() {
        initFaceDetector(); 
        connectToBareMetalCore();
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
                    byte[] buffer = new byte[16];
                    while (isConnected) {
                        int read = 0;
                        while (read < 16) {
                            int count = mIn.read(buffer, read, 16 - read);
                            if (count < 0) throw new Exception("EOF");
                            read += count;
                        }
                        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
                        dispatchSafeDrag(bb.getFloat(0), bb.getFloat(4), bb.getFloat(8), bb.getFloat(12));
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
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build();
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
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("OmegaVision", capW, capH, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mImageReader.getSurface(), null, null);
        
        mImageReader.setOnImageAvailableListener(reader -> {
            long now = System.currentTimeMillis();
            if (now - lastFrameTime < 40) {
                Image drop = reader.acquireLatestImage();
                if (drop != null) drop.close();
                return;
            }
            lastFrameTime = now;

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
                    float bestX = -1, bestY = -1, bestHo = 40.0f;
                    boolean found = false;

                    if (isConnected && !faces.isEmpty()) {
                        float imgCenterX = (image.getWidth() * SCALE_FACTOR) / 2.0f;
                        float imgCenterY = (image.getHeight() * SCALE_FACTOR) / 2.0f;
                        float minDistSq = Float.MAX_VALUE;
                        
                        for (Face face : faces) {
                            float fx = face.getBoundingBox().exactCenterX() * SCALE_FACTOR;
                            float fy = face.getBoundingBox().exactCenterY() * SCALE_FACTOR;
                            float distSq = (fx - imgCenterX)*(fx - imgCenterX) + (fy - imgCenterY)*(fy - imgCenterY);
                            if (distSq < minDistSq) { 
                                minDistSq = distSq; 
                                bestX = fx; bestY = fy;
                                bestHo = face.getBoundingBox().height() * SCALE_FACTOR;
                                found = true;
                            }
                        }
                    }

                    if (found) {
                        ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                        bb.putFloat(bestX); bb.putFloat(bestY); bb.putFloat(bestHo); bb.putFloat(0.0f);
                        try { mOut.write(bb.array()); } catch (Exception e) {}
                    }
                })
                .addOnCompleteListener(task -> isProcessing.set(false));
    }

    public void dispatchSafeDrag(float sx, float sy, float ex, float ey) {
        if (isGestureRunning) return; 
        
        if (mLastEndX != -1f) {
            sx = mLastEndX;
            sy = mLastEndY;
        }
        
        float safeTopZone = getResources().getDisplayMetrics().heightPixels * 0.65f;
        if (sy > safeTopZone) sy = safeTopZone;
        if (ey > safeTopZone) ey = safeTopZone;
        
        float dx = ex - sx;
        float dy = ey - sy;
        float dist = (float)Math.sqrt(dx*dx + dy*dy);
        
        // 🛡️ SECONDARY FIREWALL: Chặn đứng MỌI cú vuốt < 15px (Tuyệt đối không cho Android biến thành TAP)
        if (dist < 15.0f) return; 
        
        // 🚀 DYNAMIC DURATION: Vuốt càng dài, thời gian càng lâu để Android luôn nhận diện là DRAG (Không bị quán tính Fling)
        int duration = 30 + (int)(dist * 1.2f); 
        if (duration > 100) duration = 100;
        
        isGestureRunning = true;
        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);
        
        final float fEx = ex;
        final float fEy = ey;
        
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, duration);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) { 
                isGestureRunning = false; 
                mLastEndX = fEx; 
                mLastEndY = fEy; 
            }
            @Override public void onCancelled(GestureDescription gestureDescription) { 
                isGestureRunning = false; 
                mLastEndX = -1f; 
                mLastEndY = -1f; 
            }
        }, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        super.onDestroy();
        isConnected = false;
        if (mVirtualDisplay != null) mVirtualDisplay.release();
        if (mMediaProjection != null) mMediaProjection.stop();
        try { if (mSocket != null) mSocket.close(); } catch (Exception e) {}
    }
}

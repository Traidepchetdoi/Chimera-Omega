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
import android.util.Log;
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
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TouchService extends AccessibilityService {
    private static final String TAG = "OmegaBareMetal";
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private FaceDetector mFaceDetector;
    private Handler mHandler = new Handler();
    
    // 🔥 RAW TCP BINARY BRIDGE
    private Socket mSocket;
    private DataOutputStream mOut;
    private DataInputStream mIn;
    private boolean isConnected = false;
    private Thread mReadThread;

    @Override
    public void onServiceConnected() {
        startTouchServer(); 
        initFaceDetector(); 
        connectToBareMetalCore();
    }

    private void connectToBareMetalCore() {
        new Thread(() -> {
            try {
                mSocket = new Socket("127.0.0.1", 8082);
                mSocket.setTcpNoDelay(true); // Tắt thuật toán Nagle
                mOut = new DataOutputStream(mSocket.getOutputStream());
                mIn = new DataInputStream(mSocket.getInputStream());
                isConnected = true;
                Log.i(TAG, "🔥 RAW TCP BINARY LINK ESTABLISHED");
                
                // Luồng đọc lệnh vuốt nhị phân từ C++
                mReadThread = new Thread(() -> {
                    byte[] buffer = new byte[16];
                    while (isConnected) {
                        try {
                            mIn.readFully(buffer);
                            ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
                            float sx = bb.getFloat();
                            float sy = bb.getFloat();
                            float ex = bb.getFloat();
                            float ey = bb.getFloat();
                            dispatchSwipe((int)ex, (int)ey, 1);
                        } catch (Exception e) { break; }
                    }
                });
                mReadThread.start();
            } catch (Exception e) { Log.e(TAG, "Core connection failed"); }
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
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();
        mFaceDetector = FaceDetection.getClient(options);
    }

    private void startVision(int code, Intent data) {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mMediaProjection = mgr.getMediaProjection(code, data);
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(metrics);
        int mWidth = metrics.widthPixels / 3;
        int mHeight = metrics.heightPixels / 3;
        int mDensity = metrics.densityDpi;

        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("OmegaVision",
                mWidth, mHeight, mDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);

        mImageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                processImage(image);
                image.close();
            }
        }, mHandler);
    }

    private void processImage(@NonNull Image image) {
        InputImage inputImage = InputImage.fromMediaImage(image, 0);
        Task<List<Face>> result = mFaceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    for (Face face : faces) {
                        if (isConnected) {
                            try {
                                // 🔥 GỬI 16 BYTES THÔ VÀO C++ (ZERO JSON)
                                mOut.writeFloat(face.getBoundingBox().exactCenterX() * 3);
                                mOut.writeFloat(face.getBoundingBox().exactCenterY() * 3);
                                mOut.writeFloat(face.getBoundingBox().height() * 3);
                                mOut.writeFloat(face.getHeadEulerAngleX());
                            } catch (Exception e) {}
                        }
                    }
                });
    }

    private void startTouchServer() {} // Dummy, C++ handles the server now

    public void dispatchSwipe(int x, int y, int duration) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, Math.max(duration, 1));
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
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

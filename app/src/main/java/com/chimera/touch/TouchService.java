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
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

public class TouchService extends AccessibilityService {
    private static final String TAG = "ChimeraZero";
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private FaceDetector mFaceDetector;
    private MyWebSocketClient mWsClient; 
    private Handler mHandler = new Handler();
    private WsServer mWsServer;
    
    // ⚡ BIẾN LƯU TỶ LỆ MÀN HÌNH (Dùng để nhân ngược tọa độ)
    private int mScreenWidth, mScreenHeight;
    private int mCaptureWidth, mCaptureHeight;

    @Override
    public void onServiceConnected() {
        Log.i(TAG, "⚡ Zero-Latency Service Connected");
        startTouchServer(); 
        initFaceDetector(); 
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
        
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
        
        // ⚡ GIẢM 3 LẦN ĐỘ PHÂN GIẢI (Tăng tốc ML Kit gấp 9 lần)
        mCaptureWidth = mScreenWidth / 3;
        mCaptureHeight = mScreenHeight / 3;
        int mDensity = metrics.densityDpi;

        mImageReader = ImageReader.newInstance(mCaptureWidth, mCaptureHeight, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("ChimeraZero",
                mCaptureWidth, mCaptureHeight, mDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);

        mImageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                processImage(image);
                image.close();
            }
        }, mHandler);

        try {
            mWsClient = new MyWebSocketClient(new URI("ws://127.0.0.1:8082"));
            mWsClient.connect();
        } catch (Exception e) { Log.e(TAG, "WS Err: " + e.getMessage()); }
    }

    private void processImage(@NonNull Image image) {
        InputImage inputImage = InputImage.fromMediaImage(image, 0);
        Task<List<Face>> result = mFaceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    for (Face face : faces) {
                        if (mWsClient != null && mWsClient.isOpen()) {
                            // ⚡ NHÂN 3 TỌA ĐỘ ĐỂ KHỚP VỚI MÀN HÌNH THẬT
                            float x = face.getBoundingBox().exactCenterX() * 3;
                            float y = face.getBoundingBox().exactCenterY() * 3;
                            float ho = face.getBoundingBox().height() * 3;
                            float pitch = face.getHeadEulerAngleX(); 

                            String json = String.format(
                                "{\"TARGET\":true, \"x\":%.1f, \"y\":%.1f, \"headOffset\":%.1f, \"pitch\":%.1f}",
                                x, y, ho, pitch
                            );
                            mWsClient.send(json);
                        }
                    }
                });
    }

    private void startTouchServer() {
        mWsServer = new WsServer(new InetSocketAddress(8083));
        mWsServer.start();
    }

    // ⚡ ÉP ANDROID VUỐT VỚI TỐC ĐỘ ÁNH SÁNG (DURATION = 1ms)
    public void dispatchSwipe(int x, int y, int duration) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 1); // 1ms Teleport
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        super.onDestroy();
        if (mVirtualDisplay != null) mVirtualDisplay.release();
        if (mMediaProjection != null) mMediaProjection.stop();
        if (mWsServer != null) try { mWsServer.stop(); } catch (Exception e) {}
        if (mWsClient != null) mWsClient.close();
    }

    private class MyWebSocketClient extends WebSocketClient {
        public MyWebSocketClient(URI serverUri) { super(serverUri); }
        @Override public void onOpen(ServerHandshake h) { Log.i(TAG, "⚡ Vision Connected"); }
        @Override public void onMessage(String msg) {}
        @Override public void onClose(int c, String r, boolean b) {}
        @Override public void onError(Exception ex) {}
    }

    private class WsServer extends WebSocketServer {
        public WsServer(InetSocketAddress addr) { super(addr); }
        @Override public void onOpen(WebSocket conn, ClientHandshake h) {}
        @Override public void onMessage(WebSocket conn, String msg) {
            try {
                String[] p = msg.split(",");
                if (p.length == 4 && msg.startsWith("GESTURE")) {
                    int tx = (int) Float.parseFloat(p[2]);
                    int ty = (int) Float.parseFloat(p[3]);
                    dispatchSwipe(tx, ty, 1);
                }
            } catch (Exception e) {}
        }
        @Override public void onClose(WebSocket conn, int c, String r, boolean b) {}
        @Override public void onError(WebSocket conn, Exception e) {}
        @Override public void onStart() {}
    }
                                                                }

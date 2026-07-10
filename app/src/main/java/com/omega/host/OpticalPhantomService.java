package com.omega.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.core.app.NotificationCompat;

public class OpticalPhantomService extends Service {
    // CỔNG NHẬN DỮ LIỆU TỪ MAINACTIVITY (BYPASS PARCELABLE LIMIT)
    public static int mResultCode = -1;
    public static Intent mResultIntent = null;

    private WindowManager windowManager;
    private ImageView ghostReticle; // Tâm súng bóng ma (Ghost Reticle)
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection mediaProjection;
    
    private int screenWidth;
    private int screenHeight;

    // NẠP LÕI C++ SWAR
    static { System.loadLibrary("omega_core"); }
    
    // CHỮ KÝ JNI ĐỒNG BỘ VỚI C++
    public native float[] processOpticalFrame(HardwareBuffer buffer, int w, int h);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, createNotification());
        setupOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mResultCode != -1 && mResultIntent != null) {
            engageOpticalTrap(mResultCode, mResultIntent);
        }
        return START_STICKY; // Ép OS hồi sinh Service nếu bị giết
    }

    private void setupOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // Tạo tâm súng bóng ma (Sử dụng icon có sẵn của Android để không cần file ảnh)
        ghostReticle = new ImageView(this);
        ghostReticle.setImageResource(android.R.drawable.ic_menu_crop); // Hình tâm súng giả lập
        ghostReticle.setAlpha(0.8f);
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                150, 150, // Kích thước tâm súng
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | 
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
                
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0; params.y = 0;
        
        windowManager.addView(ghostReticle, params);
    }

    private void engageOpticalTrap(int resultCode, Intent data) {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mgr.getMediaProjection(resultCode, data);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // BẪY KHUNG HÌNH: ĐỔ THẲNG ÁNH SÁNG TỪ GPU VÀO IMAGEREADER
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("OMEGA_RETINA", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);

        // VÒNG LẶP QUÉT QUANG HỌC
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage(); // Chỉ lấy frame mới nhất, chống lag
            if (image != null) {
                HardwareBuffer hwBuffer = image.getHardwareBuffer();
                
                // NÉM XUỐNG C++ ĐỂ TÍNH TOÁN TỌA ĐỘ ĐẦU ĐỊCH
                float[] result = processOpticalFrame(hwBuffer, screenWidth, screenHeight);
                hwBuffer.close();
                image.close();

                if (result != null && result.length >= 3 && result[2] == 1.0f) { // Đã khóa mục tiêu
                    float targetX = result[0];
                    float targetY = result[1];
                    
                    // DỊCH CHUYỂN TÂM SÚNG BÓNG MA LÊN MÀN HÌNH
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(() -> {
                        if (ghostReticle != null && ghostReticle.isAttachedToWindow()) {
                            WindowManager.LayoutParams p = (WindowManager.LayoutParams) ghostReticle.getLayoutParams();
                            p.x = (int)targetX - 75; // Căn giữa tâm súng
                            p.y = (int)targetY - 75;
                            try {
                                windowManager.updateViewLayout(ghostReticle, p);
                            } catch (Exception e) { /* Bỏ qua lỗi layout */ }
                        }
                    });
                }
            }
        }, null);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel("OMEGA_VISION", "Omega Retina", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, "OMEGA_VISION")
                .setContentTitle("Omega System")
                .setContentText("Đang đồng bộ quang học...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
    
    @Override
    public void onDestroy() {
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (ghostReticle != null && ghostReticle.isAttachedToWindow()) windowManager.removeView(ghostReticle);
        super.onDestroy();
    }
}
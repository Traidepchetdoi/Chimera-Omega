package com.omega.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.DisplayMetrics;
import androidx.core.app.NotificationCompat;
import java.nio.ByteBuffer;

public class OpticalPhantomService extends Service {
    public static int mResultCode = -1;
    public static Intent mResultIntent = null;

    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection mediaProjection;
    private int screenWidth, screenHeight;

    static { System.loadLibrary("omega_core"); }
    public native float[] processOpticalFrame(ByteBuffer buffer, int w, int h, int rowStride);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mResultCode != -1 && mResultIntent != null) {
            engageOpticalTrap(mResultCode, mResultIntent);
        }
        return START_STICKY;
    }

    private void engageOpticalTrap(int resultCode, Intent data) {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mgr.getMediaProjection(resultCode, data);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("OMEGA_RETINA", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                Image.Plane[] planes = image.getPlanes();
                if (planes.length > 0) {
                    ByteBuffer buffer = planes[0].getBuffer();
                    int rowStride = planes[0].getRowStride();
                    
                    float[] result = processOpticalFrame(buffer, screenWidth, screenHeight, rowStride);
                    
                    if (result != null && result.length >= 4) {
                        // 1. TRUYỀN DELTA CHO CÁNH TAY NHÍCH VI MÔ
                        if (OmegaMacroService.instance != null) {
                            OmegaMacroService.instance.injectMicroSwipe(result[0], result[1], result[2] == 1.0f);
                        }
                        
                        // 2. INFINITY SỨC (AUTO MEDKIT)
                        if (result[3] == 1.0f && OmegaMacroService.instance != null) {
                            OmegaMacroService.instance.injectTap(screenWidth - 200, screenHeight - 300);
                        }
                    }
                }
                image.close();
            }
        }, null);
    }

    private void createNotificationChannel() {
        // IMPORTANCE_MIN: Chỉ hiện dấu chấm nhỏ trong khay, KHÔNG hiện trên thanh Status Bar, KHÔNG có tiếng
        NotificationChannel channel = new NotificationChannel("OMEGA_VISION", "System Sync", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false); // Tắt badge icon trên icon app
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, "OMEGA_VISION")
                .setContentTitle("Android System") // Giả danh thông báo hệ thống
                .setContentText("Syncing background processes...")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // Icon bluetooth giả mạo
                .setPriority(NotificationCompat.PRIORITY_MIN) // Mức ưu tiên thấp nhất
                .build();
    }

    @Override 
    public IBinder onBind(Intent intent) { 
        return null; 
    }
    
    @Override 
    public void onDestroy() {
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        super.onDestroy();
    }
}
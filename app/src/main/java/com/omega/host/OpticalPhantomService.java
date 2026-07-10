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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.core.app.NotificationCompat;
import java.nio.ByteBuffer;

public class OpticalPhantomService extends Service {
    public static int mResultCode = -1;
    public static Intent mResultIntent = null;

    private WindowManager windowManager;
    private ImageView ghostReticle;
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
        setupOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mResultCode != -1 && mResultIntent != null) {
            engageOpticalTrap(mResultCode, mResultIntent);
        }
        return START_STICKY;
    }

    private void setupOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        ghostReticle = new ImageView(this);
        ghostReticle.setImageResource(android.R.drawable.ic_menu_crop); // Tâm súng giả
        ghostReticle.setAlpha(0.8f);
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                150, 150,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | 
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(ghostReticle, params);
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
                        // 1. AIM LOCK
                        if (result[2] == 1.0f) {
                            final float tx = result[0];
                            final float ty = result[1];
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (ghostReticle != null && ghostReticle.isAttachedToWindow()) {
                                    WindowManager.LayoutParams p = (WindowManager.LayoutParams) ghostReticle.getLayoutParams();
                                    p.x = (int)tx - 75;
                                    p.y = (int)ty - 75;
                                    try { windowManager.updateViewLayout(ghostReticle, p); } catch (Exception e) {}
                                }
                            });
                        }
                        
                        // 2. INFINITY HEALTH (AUTO MEDKIT)
                        if (result[3] == 1.0f && OmegaMacroService.instance != null) {
                            // Tọa độ nút Medkit (Góc phải dưới - Tùy chỉnh theo game)
                            int medkitX = screenWidth - 200; 
                            int medkitY = screenHeight - 300;
                            OmegaMacroService.instance.injectTap(medkitX, medkitY);
                        }
                    }
                }
                image.close();
            }
        }, null);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel("OMEGA_VISION", "Omega Retina", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, "OMEGA_VISION")
                .setContentTitle("Omega System")
                .setContentText("Eternity Protocol Active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (ghostReticle != null) windowManager.removeView(ghostReticle);
        super.onDestroy();
    }
}
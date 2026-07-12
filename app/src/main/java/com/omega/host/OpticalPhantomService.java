package com.omega.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import androidx.core.app.NotificationCompat;
import java.nio.ByteBuffer;

public class OpticalPhantomService extends Service implements android.hardware.SensorEventListener {
    public static int mResultCode = -1;
    public static Intent mResultIntent = null;

    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection mediaProjection;
    private int screenWidth, screenHeight;
    private android.hardware.SensorManager sensorManager;
    
    private Vibrator vibrator;
    private long lastHapticTick = 0;
    
    // [OMEGA WAKELOCK] Ép CPU thức vĩnh viễn
    private PowerManager.WakeLock wakeLock;

    static { System.loadLibrary("omega_core"); }
    
    public native double[] processOpticalFrame(ByteBuffer buffer, int w, int h, int rowStride);
    public native void updateGyroVector(float rotX, float rotY);

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 1. Kích hoạt WakeLock (PARTIAL_WAKE_LOCK: Giữ CPU chạy, cho phép tắt màn hình)
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Omega:CoreLock");
        wakeLock.acquire();
        
        createNotificationChannel();
        startForeground(1, createNotification());
        
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        
        sensorManager = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
        android.hardware.Sensor gyro = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);
        if (gyro != null) sensorManager.registerListener(this, gyro, 1000); 
    }

    @Override
    public void onSensorChanged(android.hardware.SensorEvent event) {
        if (event.sensor.getType() == android.hardware.Sensor.TYPE_GYROSCOPE) {
            updateGyroVector(event.values[0], event.values[1]);
        }
    }
    @Override public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mResultCode != -1 && mResultIntent != null) {
            engageOpticalTrap(mResultCode, mResultIntent);
        }
        return START_STICKY; // Yêu cầu OS tự động hồi sinh Service nếu bị giết
    }

    private void engageOpticalTrap(int resultCode, Intent data) {
        try {
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = mgr.getMediaProjection(resultCode, data);
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels;

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
                        
                        double[] result = processOpticalFrame(buffer, screenWidth, screenHeight, rowStride);
                        
                        if (result != null && result.length >= 5) {
                            boolean isLocked = (result[3] == 1.0);
                            double dist = result[2];
                            
                            long now = System.currentTimeMillis();
                            if (isLocked && dist > 1.5 && vibrator != null && vibrator.hasVibrator()) {
                                long tickInterval = (long) Math.max(10, dist / 5.0); 
                                if (now - lastHapticTick > tickInterval) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(5, VibrationEffect.EFFECT_TICK));
                                    } else {
                                        vibrator.vibrate(5);
                                    }
                                    lastHapticTick = now;
                                }
                            }

                            if (OmegaMacroService.instance != null) {
                                OmegaMacroService.instance.injectMicroSwipe(result[0], result[1], isLocked);
                            }
                            if (result[4] == 1.0 && OmegaMacroService.instance != null) {
                                OmegaMacroService.instance.injectTap(screenWidth - 200, screenHeight - 300);
                            }
                        }
                    }
                    image.close();
                }
            }, null);
        } catch (Exception e) {
            // Bẫy lỗi: Nuốt ngoại lệ nếu OS chặn MediaProjection
            e.printStackTrace();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_DEFAULT: Đảm bảo hiện icon trên Status Bar
            NotificationChannel channel = new NotificationChannel("OMEGA_VISION", "Omega Core", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null); // Tắt tiếng
            channel.setVibrationPattern(null); // Tắt rung của thông báo
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "OMEGA_VISION")
                .setContentTitle("Omega Core")
                .setContentText("Flat-Warp & Scope Comp. Active")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true) // Không cho vuốt tắt
                .setOnlyAlertOnce(true); // Chỉ báo động 1 lần duy nhất
                
        // Bypass một số OS chặn category mặc định
        builder.setCategory(Notification.CATEGORY_SERVICE); 
        
        return builder.build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    
    @Override public void onDestroy() {
        // Giải phóng tài nguyên khi bị giết
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        super.onDestroy();
    }
}
package com.omega.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
    private PowerManager.WakeLock wakeLock;

    static { System.loadLibrary("omega_core"); }
    public native double[] processOpticalFrame(ByteBuffer buffer, int w, int h, int rowStride);
    public native void updateGyroVector(float rotX, float rotY);

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Omega:CoreLock");
        wakeLock.acquire();
        
        createNotificationChannel();
        
        // [OMEGA EXCEPTION TRAP] BẪY NGOẠI LỆ FOREGROUND
        // Chặn đứng việc OS giết Service làm Crash toàn bộ App (Lỗi "bị đẩy ra")
        try {
            startForeground(1, createNotification());
        } catch (Exception e) {
            // Nuốt ngoại lệ ForegroundServiceStartNotAllowedException
            // App giao diện sẽ KHÔNG BAO GIỜ CRASH. Service sẽ âm thầm hồi sinh ở lần gọi sau.
            e.printStackTrace();
        }
        
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        sensorManager = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
        android.hardware.Sensor gyro = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);
        if (gyro != null) sensorManager.registerListener(this, gyro, 1000); 
    }

    @Override public void onSensorChanged(android.hardware.SensorEvent event) {
        if (event.sensor.getType() == android.hardware.Sensor.TYPE_GYROSCOPE) updateGyroVector(event.values[0], event.values[1]);
    }
    @Override public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mResultCode != -1 && mResultIntent != null) engageOpticalTrap(mResultCode, mResultIntent);
        return START_STICKY;
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
                        double[] result = processOpticalFrame(buffer, screenWidth, screenHeight, planes[0].getRowStride());
                        if (result != null && result.length >= 5) {
                            boolean isLocked = (result[3] == 1.0);
                            double dist = result[2];
                            long now = System.currentTimeMillis();
                            if (isLocked && dist > 1.5 && vibrator != null && vibrator.hasVibrator()) {
                                long tickInterval = (long) Math.max(10, dist / 5.0); 
                                if (now - lastHapticTick > tickInterval) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(5, VibrationEffect.EFFECT_TICK));
                                    else vibrator.vibrate(5);
                                    lastHapticTick = now;
                                }
                            }
                            if (OmegaMacroService.instance != null) OmegaMacroService.instance.injectMicroSwipe(result[0], result[1], isLocked);
                            if (result[4] == 1.0 && OmegaMacroService.instance != null) OmegaMacroService.instance.injectTap(screenWidth - 200, screenHeight - 300);
                        }
                    }
                    image.close();
                }
            }, null);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_HIGH: Bắt buộc để kích hoạt FullScreenIntent bypass Gaming Mode
            NotificationChannel channel = new NotificationChannel("OMEGA_VISION", "Omega Core", NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, null);
            channel.setVibrationPattern(null);
            channel.setShowBadge(false);
            channel.setBypassDnd(true); // [OMEGA BYPASS] Xuyên thủng chế độ Không Làm Phiền
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        // Tạo một Intent giả lập cuộc gọi đến để ép OS phải hiện thông báo
        Intent fullScreenIntent = new Intent(this, MainActivity.class);
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, "OMEGA_VISION")
                .setContentTitle("Omega Core System")
                .setContentText("Flat-Warp Engine & IMU Fusion Active")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Ép ưu tiên cao nhất
                .setCategory(Notification.CATEGORY_CALL) // Giả danh cuộc gọi để bypass Task Killer
                .setFullScreenIntent(fullScreenPendingIntent, true) // [OMEGA OVERRIDE] Xé rách Gaming Mode
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        super.onDestroy();
    }
}
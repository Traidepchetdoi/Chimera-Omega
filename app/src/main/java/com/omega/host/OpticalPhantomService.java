package com.omega.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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

public class OpticalPhantomService extends Service implements SensorEventListener {
    public static int mResultCode = -1;
    public static Intent mResultIntent = null;

    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection mediaProjection;
    private int screenWidth, screenHeight;
    private SensorManager sensorManager;

    static { System.loadLibrary("omega_core"); }
    
    public native double[] processOpticalFrame(ByteBuffer buffer, int w, int h, int rowStride);
    // [OMEGA IMU] Cổng truyền dữ liệu Gyroscope xuống C++
    public native void updateGyroVector(float rotX, float rotY);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, createNotification());
        
        // Đánh cắp Giác Quan Tiền Đình (Gyroscope) ở tần số cao nhất (1000Hz / 1ms)
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, 1000); // 1000 microsecond = 1ms
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            // event.values[0] = Xoay quanh trục X (Gật lên xuống)
            // event.values[1] = Xoay quanh trục Y (Lắc trái phải)
            // Ném thẳng xuống Nhân C++ mà không thông qua Garbage Collector
            updateGyroVector(event.values[0], event.values[1]);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mResultCode != -1 && mResultIntent != null) engageOpticalTrap(mResultCode, mResultIntent);
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
                    
                    double[] result = processOpticalFrame(buffer, screenWidth, screenHeight, rowStride);
                    
                    if (result != null && result.length >= 4) {
                        if (OmegaMacroService.instance != null) {
                            OmegaMacroService.instance.injectMicroSwipe(result[0], result[1], result[2] == 1.0);
                        }
                        if (result[3] == 1.0 && OmegaMacroService.instance != null) {
                            OmegaMacroService.instance.injectTap(screenWidth - 200, screenHeight - 300);
                        }
                    }
                }
                image.close();
            }
        }, null);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel("OMEGA_VISION", "System Sync", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, "OMEGA_VISION")
                .setContentTitle("Android System")
                .setContentText("IMU-Fusion Active...")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        super.onDestroy();
    }
}
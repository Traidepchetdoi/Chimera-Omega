package com.omega.host;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private static final int OVERLAY_REQ = 1;
    private static final int MEDIA_REQ = 2;
    private static final int NOTIFICATION_REQ = 3;
    private static final int BATTERY_REQ = 4;

    private TextView tv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.BLACK);
        layout.setPadding(50, 50, 50, 50);
        layout.setGravity(Gravity.CENTER);

        tv = new TextView(this);
        tv.setText("OMEGA ETERNITY SYSTEM\nStatus: Awaiting Neural Sync...");
        tv.setTextColor(Color.GREEN);
        tv.setTextSize(18f);
        tv.setGravity(Gravity.CENTER);
        layout.addView(tv);

        Button btnAcc = new Button(this);
        btnAcc.setText("1. ENABLE ACCESSIBILITY (Macro)");
        btnAcc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        layout.addView(btnAcc);

        Button btnStart = new Button(this);
        btnStart.setText("2. START OPTICAL LOCK");
        btnStart.setOnClickListener(v -> checkPermissionsAndStart());
        layout.addView(btnStart);

        setContentView(layout);
    }

    private void checkPermissionsAndStart() {
        // 1. Overlay
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), OVERLAY_REQ);
            return;
        }

        // 2. Notification (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQ);
                return;
            }
        }

        // 3. BỎ QUA TỐI ƯU HÓA PIN (BATTERY BYPASS)
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, BATTERY_REQ);
                return;
            }
        }

        // 4. MediaProjection
        startCapture();
    }

    private void startCapture() {
        // Kiểm tra Service đã chạy chưa để tránh start đúp gây crash
        if (isMyServiceRunning(OpticalPhantomService.class)) {
            tv.setText("[STATUS] SYSTEM ALREADY ACTIVE.\nHide this app now.");
            moveTaskToBack(true);
            return;
        }

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_REQ);
    }

    private boolean isMyServiceRunning(Class<?> serviceClass) {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQ) checkPermissionsAndStart();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == OVERLAY_REQ || requestCode == BATTERY_REQ) {
            checkPermissionsAndStart();
        } else if (requestCode == MEDIA_REQ && resultCode == RESULT_OK) {
            OpticalPhantomService.mResultCode = resultCode;
            OpticalPhantomService.mResultIntent = data;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(new Intent(this, OpticalPhantomService.class));
            } else {
                startService(new Intent(this, OpticalPhantomService.class));
            }
            
            tv.setText("[STATUS] NEURAL SYNC ACTIVE.\nMediaProjection Engaged.\nHide this app now.");
            
            // [OMEGA ANCHOR] TUYỆT ĐỐI KHÔNG finish(). 
            // Chỉ ẩn xuống nền để giữ Activity sống làm "mỏ neo" cho MediaProjection.
            moveTaskToBack(true); 
        }
    }
}
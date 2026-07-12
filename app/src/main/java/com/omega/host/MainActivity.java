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
import android.os.Handler;
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
    private static final int WRITE_SETTINGS_REQ = 5;

    private TextView tv;
    private Handler waitHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.BLACK);
        layout.setPadding(50, 50, 50, 50);
        layout.setGravity(Gravity.CENTER);

        tv = new TextView(this);
        tv.setText("OMEGA ETERNITY SYSTEM\nHonor MagicOS 10 Bypass...");
        tv.setTextColor(Color.GREEN);
        tv.setTextSize(18f);
        tv.setGravity(Gravity.CENTER);
        layout.addView(tv);

        Button btnAcc = new Button(this);
        btnAcc.setText("1. ENABLE ACCESSIBILITY");
        btnAcc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        layout.addView(btnAcc);

        Button btnStart = new Button(this);
        btnStart.setText("2. START OPTICAL LOCK");
        btnStart.setOnClickListener(v -> checkPermissionsAndStart());
        layout.addView(btnStart);

        setContentView(layout);
    }

    private void checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), OVERLAY_REQ);
            return;
        }
        if (!Settings.System.canWrite(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())), WRITE_SETTINGS_REQ);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQ);
                return;
            }
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            startActivityForResult(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())), BATTERY_REQ);
            return;
        }
        startCapture();
    }

    private void startCapture() {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_REQ);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQ) checkPermissionsAndStart();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_REQ || requestCode == BATTERY_REQ || requestCode == WRITE_SETTINGS_REQ) {
            checkPermissionsAndStart();
        } else if (requestCode == MEDIA_REQ && resultCode == RESULT_OK) {
            // [OMEGA STATIC VAULT] Bỏ Token vào Két Sắt, KHÔNG truyền qua Intent
            MediaProjectionVault.store(resultCode, data);
            
            Intent serviceIntent = new Intent(this, OpticalPhantomService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            
            tv.setText("[STATUS] WAITING FOR FIRST FRAME...\nDO NOT HIDE APP YET.");
            tv.setTextColor(Color.YELLOW);
            
            // [OMEGA TIGHT SYNC] Chờ Service bắt được Frame 0 rồi mới ẩn App
            waitForServiceReady();
        }
    }

    private void waitForServiceReady() {
        waitHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (MediaProjectionVault.isServiceReady) {
                    tv.setText("[STATUS] NEURAL SYNC IMMORTAL.\nMagicOS Bypass Complete.\nHide this app now.");
                    tv.setTextColor(Color.GREEN);
                    // Chỉ ẩn xuống nền KHI VÀ CHỈ KHI Service đã an toàn
                    moveTaskToBack(true); 
                } else {
                    // Tiếp tục chờ thêm 500ms
                    waitForServiceReady();
                }
            }
        }, 500);
    }
}
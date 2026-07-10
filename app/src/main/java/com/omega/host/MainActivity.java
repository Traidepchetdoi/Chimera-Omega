package com.omega.host;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
        // 1. Xin quyền Overlay
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), OVERLAY_REQ);
            return;
        }

        // 2. Xin quyền Thông Báo (Bắt buộc cho Android 13+ để giữ sống MediaProjection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQ);
                return;
            }
        }

        // 3. Kích hoạt MediaProjection
        startCapture();
    }

    private void startCapture() {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_REQ);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQ) {
            // Dù người dùng từ chối, ta vẫn cố gắng start (nhưng có thể sẽ bị OS giết ngầm)
            startCapture();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_REQ && Settings.canDrawOverlays(this)) {
            checkPermissionsAndStart();
        } else if (requestCode == MEDIA_REQ && resultCode == RESULT_OK) {
            OpticalPhantomService.mResultCode = resultCode;
            OpticalPhantomService.mResultIntent = data;
            startForegroundService(new Intent(this, OpticalPhantomService.class));
            
            tv.setText("[STATUS] NEURAL SYNC ACTIVE.\nMediaProjection Engaged.\nHide this app now.");
            
            // Tự động ẩn app xuống nền để全屏 game
            moveTaskToBack(true); 
        }
    }
}
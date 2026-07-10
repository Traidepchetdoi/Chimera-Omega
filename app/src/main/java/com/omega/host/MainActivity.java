package com.omega.host;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    
    private static final int REQ_NOTIFICATIONS = 101;
    private static final int REQ_OVERLAY = 102;
    
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        
        TextView tv = new TextView(this);
        tv.setText("🩸 OMEGA HOST - SINGULARITY\n\nĐang xin quyền sinh tồn...");
        tv.setTextSize(16);
        tv.setPadding(40, 40, 40, 40);
        
        Button b = new Button(this);
        b.setText("⚡ KÍCH HOẠT & VÀO TRẬN");
        b.setTextSize(18);
        
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.addView(tv);
        layout.addView(b);
        setContentView(layout);
        
        // 🛡️ 1. Xin quyền POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            }
        }
        
        // 🛡️ 2. Xin quyền IGNORE_BATTERY (Chống Doze Mode)
        try {
            Intent batteryIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            batteryIntent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(batteryIntent);
        } catch (Exception ignored) {}
        
        // 🛡️ 3. Xin quyền OVERLAY (Cho OEM khó tính)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent overlayIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(overlayIntent, REQ_OVERLAY);
        }
        
        b.setOnClickListener(v -> {
            // Bước 1: Mở cài đặt Accessibility
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            
            // Bước 2: Xin quyền MediaProjection
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), 1);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1 && res == RESULT_OK) {
            startService(new Intent(this, HostService.class)
                .putExtra("code", res)
                .putExtra("data", data));
            finish(); // Đóng MainActivity, chỉ để Service chạy ngầm
        }
    }
}
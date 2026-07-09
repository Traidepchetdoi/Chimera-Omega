package com.omega.host;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.net.Uri;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        Button b = new Button(this);
        b.setText("KÍCH HOẠT OMEGA HOST\n(Bấm vào đây để cấp quyền sống sót)");
        b.setTextSize(18);
        setContentView(b);
        
        // 🛡️ 1. ÉP XUNG QUYỀN THÔNG BÁO (Chống Android 13+ giết ngầm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
        
        // 🛡️ 2. ĐÒI QUYỀN BỎ QUA TỐI ƯU HÓA PIN (Chống LMK và Battery Saver giết app)
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) { /* Bỏ qua nếu máy không hỗ trợ */ }

        b.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), 1);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && res == RESULT_OK) {
            startService(new Intent(this, HostService.class)
                .putExtra("code", res)
                .putExtra("data", data));
            finish();
        }
    }
}
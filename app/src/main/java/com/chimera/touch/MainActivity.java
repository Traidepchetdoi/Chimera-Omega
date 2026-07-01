package com.chimera.touch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.BLACK);
        layout.setPadding(50, 50, 50, 50);

        TextView title = new TextView(this);
        title.setText("🌌 OMEGA CORE v37.0");
        title.setTextColor(Color.CYAN);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);
        
        Button btnAccess = new Button(this);
        btnAccess.setText("1. BẬT TRỢ NĂNG (ACCESSIBILITY)");
        btnAccess.setTextColor(Color.WHITE);
        btnAccess.setBackgroundColor(Color.DKGRAY);
        btnAccess.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        layout.addView(btnAccess);

        Button btnOverlay = new Button(this);
        btnOverlay.setText("2. BẬT VẼ ĐÈ (OVERLAY)");
        btnOverlay.setTextColor(Color.WHITE);
        btnOverlay.setBackgroundColor(Color.DKGRAY);
        btnOverlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        layout.addView(btnOverlay);

        Button btnVision = new Button(this);
        btnVision.setText("3. BẮT ĐẦU THỊ GIÁC & CHIẾN ĐẤU");
        btnVision.setTextColor(Color.BLACK);
        btnVision.setBackgroundColor(Color.GREEN);
        btnVision.setOnClickListener(v -> {
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        });
        layout.addView(btnVision);

        setContentView(layout);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            Intent serviceIntent = new Intent(this, TouchService.class);
            serviceIntent.setAction("START_VISION");
            serviceIntent.putExtra("CODE", resultCode);
            serviceIntent.putExtra("DATA", data);
            
            // 🛡️ VÁ LỖI CRASH: DÙNG startService THAY VÌ startForegroundService
            startService(serviceIntent); 
            
            Toast.makeText(this, "🚀 Đã kích hoạt. Ẩn app và vào Game!", Toast.LENGTH_LONG).show();
            finish();
        }
    }
}

package com.chimera.touch;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextUtils;

public class MainActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 101;
    private MediaProjectionManager mProjectionManager;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 100, 50, 50);

        TextView tv = new TextView(this);
        tv.setText("🔓 OMEGA DEEP UNLOCK\n\nHệ thống đã bẻ khóa tầng sâu.\nTự động kích hoạt Accessibility.");
        layout.addView(tv);

        // 🧠 TỰ ĐỘNG BẬT ACCESSIBILITY BẰNG QUYỀN WRITE_SECURE_SETTINGS
        enableAccessibilityService();

        Button btnVision = new Button(this);
        btnVision.setText("Start Vision (X-Ray Eye)");
        btnVision.setOnClickListener(v -> {
            if (mProjectionManager != null) {
                startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
            }
        });
        layout.addView(btnVision);

        setContentView(layout);
    }

    // 🔓 HÀM BẺ KHÓA: TỰ BẬT ACCESSIBILITY KHÔNG CẦN VÀO CÀI ĐẶT
    private void enableAccessibilityService() {
        try {
            String service = "com.chimera.touch/.TouchService";
            
            // Đọc danh sách service đang bật
            String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            
            if (enabledServices == null || !enabledServices.contains(service)) {
                // Ép OS bật service của chúng ta
                String newServices = (enabledServices == null ? "" : enabledServices + ":") + service;
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices);
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
                Toast.makeText(this, "🔓 Accessibility Auto-Injected!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "✅ Accessibility Already Active", Toast.LENGTH_SHORT).show();
            }
            
            // Ép Start Service ngay lập tức
            startService(new Intent(this, TouchService.class));
            
        } catch (SecurityException e) {
            // Nếu chưa chạy script unlock_system.sh trên Termux
            Toast.makeText(this, "⚠️ Chạy bash ~/chimera/unlock_system.sh trong Termux trước!", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            Intent serviceIntent = new Intent(this, TouchService.class);
            serviceIntent.putExtra("CODE", resultCode);
            serviceIntent.putExtra("DATA", data);
            serviceIntent.setAction("START_VISION");
            startService(serviceIntent);
            Toast.makeText(this, "👁️ Vision Injected!", Toast.LENGTH_LONG).show();
            // Tự động đóng App để vào Game ngay
            moveTaskToBack(true); 
        }
    }
}

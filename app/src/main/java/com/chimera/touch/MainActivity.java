package com.chimera.touch; // Đảm bảo đúng package của anh

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Button btnStart;
    private TextView tvStatus;
    
    // 🚀 Launcher xin quyền MediaProjection (Quay màn hình)
    private ActivityResultLauncher<Intent> projectionLauncher;
    // 🛡️ Launcher xin quyền Overlay (Vẽ đè)
    private ActivityResultLauncher<Intent> overlayLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Giao diện tối giản (Code trực tiếp không cần XML layout)
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);
        layout.setBackgroundColor(0xFF121212);
        layout.setPadding(50, 50, 50, 50);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(0xFF00FF00);
        tvStatus.setTextSize(18);
        tvStatus.setText("🔄 Đang kiểm tra hệ thống...");
        layout.addView(tvStatus);

        btnStart = new Button(this);
        btnStart.setText("⚡ BẮT ĐẦU ĐỒ SÁT (START ESP & AIM)");
        btnStart.setTextSize(20);
        btnStart.setEnabled(false);
        btnStart.setOnClickListener(v -> igniteCore());
        layout.addView(btnStart);

        setContentView(layout);

        // Khởi tạo các Launcher
        overlayLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> checkPermissions());
        projectionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                startCoreServices(result.getResultCode(), result.getData());
            } else {
                tvStatus.setText("❌ Từ chối quay màn hình. Hệ thống tắt.");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissions(); // Kiểm tra lại mỗi khi quay lại màn hình
    }

    private void checkPermissions() {
        boolean hasOverlay = Settings.canDrawOverlays(this);
        boolean hasAccessibility = isAccessibilityServiceEnabled(TouchService.class);

        if (!hasOverlay) {
            tvStatus.setText("⚠️ THIẾU QUYỀN VẼ ĐÈ (ESP).\nĐang mở cài đặt...");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            overlayLauncher.launch(intent);
            return;
        }

        if (!hasAccessibility) {
            tvStatus.setText("⚠️ THIẾU QUYỀN TRỢ NĂNG (AIMBOT).\nHãy bật Chimera Omega trong Cài đặt.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        // Đã đủ 2 quyền nền tảng
        tvStatus.setText("✅ ĐÃ MỞ KHÓA BẢO MẬT.\nSẵn sàng tiêm ESP & Aimbot.");
        btnStart.setEnabled(true);
    }

    private void igniteCore() {
        btnStart.setEnabled(false);
        tvStatus.setText("🛰️ Đang xin quyền MediaProjection...");
        // Xin quyền quay màn hình (Bắt buộc phải có Intent từ MediaProjectionManager)
        android.media.projection.MediaProjectionManager mgr = 
            (android.media.projection.MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projectionLauncher.launch(mgr.createScreenCaptureIntent());
    }

    private void startCoreServices(int resultCode, Intent data) {
        // 1. Kích hoạt ESP Overlay (Vẽ khung xương & HUD)
        Intent espIntent = new Intent(this, ESPOverlay.class);
        startForegroundService(espIntent);

        // 2. Truyền Token MediaProjection cho TouchService (Đã chạy ngầm do Accessibility bật)
        Intent visionIntent = new Intent(this, TouchService.class);
        visionIntent.setAction("START_VISION");
        visionIntent.putExtra("CODE", resultCode);
        visionIntent.putExtra("DATA", data);
        startService(visionIntent);

        tvStatus.setText("🩸 OMEGA CORE ĐÃ KẾT NỐI.\nESP & AIMBOT ĐANG HOẠT ĐỘNG.\nHãy mở Free Fire!");
        
        // Thu nhỏ app để người dùng vào game
        moveTaskToBack(true);
    }

    // Hàm kiểm tra xem AccessibilityService có đang bật không
    private boolean isAccessibilityServiceEnabled(Class<?> serviceClass) {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String myId = getPackageName() + "/" + serviceClass.getName();
        for (AccessibilityServiceInfo info : enabledServices) {
            if (info.getId().equals(myId)) return true;
        }
        return false;
    }
}

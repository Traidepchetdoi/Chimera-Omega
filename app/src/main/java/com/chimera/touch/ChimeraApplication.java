package com.chimera.touch;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import androidx.multidex.MultiDex;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetectorOptions;

public class ChimeraApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 📦 Kích hoạt MultiDex chống crash RAM
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("ChimeraApp", "🧠 Omega Core Ignited");
        // ⚡ Nạp trước ML Kit vào RAM (Giảm 2s độ trễ lần đầu mở App)
        try {
            FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();
            FaceDetection.getClient(options);
            Log.i("ChimeraApp", "👁️ ML Kit Pre-loaded Successfully");
        } catch (Exception e) {
            Log.e("ChimeraApp", "❌ ML Kit Pre-load failed");
        }
    }
}

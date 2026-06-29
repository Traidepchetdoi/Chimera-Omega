package com.chimera.touch;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import androidx.multidex.MultiDex;
import com.google.mlkit.vision.face.FaceDetection;

public class ChimeraApplication extends Application {
    private static final String TAG = "ChimeraApp";
    
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 📦 KÍCH HOẠT MULTIDEX
        MultiDex.install(this);
        Log.i(TAG, "📦 MultiDex Installed");
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "🧠 Chimera Omega Application Started");
        Log.i(TAG, "📱 Build: " + BuildConfig.VERSION_NAME);
        Log.i(TAG, "⏰ Build Time: " + BuildConfig.BUILD_TIME);
        
        // 🧠 KHỞI TẠO SỚM ML KIT (Để giảm độ trễ khi dùng)
        try {
            FaceDetection.getClient();
            Log.i(TAG, "🧠 ML Kit Face Detector Pre-initialized");
        } catch (Exception e) {
            Log.e(TAG, "❌ ML Kit Pre-init failed: " + e.getMessage());
        }
    }
}

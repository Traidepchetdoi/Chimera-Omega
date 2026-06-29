package com.chimera.touch;
import android.app.Application;
import android.content.Context;
import android.util.Log;
import androidx.multidex.MultiDex;
import com.google.mlkit.vision.face.FaceDetection;

public class ChimeraApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("ChimeraApp", "🧠 Omega Application Started");
        try { FaceDetection.getClient(); } catch (Exception e) {}
    }
}

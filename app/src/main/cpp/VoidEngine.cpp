#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <time.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_BONE", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class BoneProjectionCore {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    double velX = 0, velY = 0; 
    
    // [OMEGA JSON SKELETON RATIOS] - ĐÓNG BĂNG TỪ FILE JSON CỦA ANH
    const double RATIO_HEAD_STAND = 0.325; // Tỷ lệ từ Tâm Thân lên Đầu khi ĐỨNG
    const double RATIO_HEAD_CROUCH = 0.480; // Tỷ lệ từ Tâm Thân lên Đầu khi CÚI / NÚP
    const double POSTURE_THRESHOLD = 0.65; // Ngưỡng chiều cao để phân biệt Đứng/Cúi
    
    // [OMEGA 2D IRON CHAIN]
    const double Kp = 2.5;             
    const double Kd = 0.15;            
    const double MIN_TENSION = 120.0;  
    const double MAX_TENSION = 400.0;

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
        
        double sumWX = 0, sumWY = 0, totalWeight = 0;
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        // 1. QUÉT KHỐI THÂN (TORSO MASS SCAN)
        // Dùng Trọng tâm Sắc độ để tìm "Khối Thân" - thứ to nhất và ổn định nhất
        for (int y = 0; y < height; y += 4) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 0; x < width; x += 4) {
                const uint8_t* p = rowPtr + (x * 4);
                double redDom = p[0] - (p[1] + p[2]) * 0.5;
                if (redDom > 40 && p[0] > 150) {
                    double weight = redDom;
                    sumWX += x * weight;
                    sumWY += y * weight;
                    totalWeight += weight;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                    pixelCount++;
                }
            }
        }

        if (totalWeight > 0 && pixelCount >= 3) { 
            // Tọa độ Tâm Khối (Chính là Torso / Họng súng của địch)
            double torsoCX = sumWX / totalWeight;
            double torsoCY = sumWY / totalWeight;
            
            int boxHeight = maxY - minY;
            int boxWidth = maxX - minX;
            
            // 2. CHIẾU RỌI KHUNG XƯƠNG JSON (SKELETON PROJECTION)
            // Tính toán xem địch đang Đứng hay Cúi dựa trên tỷ lệ Chiều Cao / Chiều Ngang
            double postureRatio = (boxWidth > 0) ? (double)boxHeight / boxWidth : 1.0;
            
            double projectedHeadY;
            if (postureRatio > POSTURE_THRESHOLD) {
                // ĐỊCH ĐANG ĐỨNG: Đầu nằm cách Tâm Thân 32.5% chiều cao hộp bao
                projectedHeadY = minY + (boxHeight * (0.5 - RATIO_HEAD_STAND));
            } else {
                // ĐỊCH ĐANG CÚI / NÚP: Đầu rụt xuống, cách Tâm Thân 48% chiều cao
                projectedHeadY = minY + (boxHeight * (0.5 - RATIO_HEAD_CROUCH));
            }

            // Tọa độ X của đầu luôn thẳng hàng với Tâm Khối (Trừ khi địch xoay lưng, nhưng ta bỏ qua để giữ sự ổn định)
            double projectedHeadX = torsoCX; 

            // 3. ĐỒNG BỘ HÓA PHẦN CỨNG & VẬN TỐC
            double currentTime = getHardwareTime();
            double dt = currentTime - prevTime;
            if (dt < 0.001) dt = 0.001;

            if (prevTime > 0) {
                double rawVX = (projectedHeadX - prevCX) / dt;
                double rawVY = (projectedHeadY - prevCY) / dt;
                velX = (velX * 0.7) + (rawVX * 0.3);
                velY = (velY * 0.7) + (rawVY * 0.3);
            }
            prevCX = projectedHeadX; prevCY = projectedHeadY; prevTime = currentTime;

            // 4. DELTA TỪ TÂM MÀN HÌNH ĐẾN "CÁI ĐẦU ẢO" (PROJECTED HEAD)
            double dx = projectedHeadX - (width / 2.0);
            double dy = projectedHeadY - (height / 2.0);

            // 5. SỢI XÍCH SẮT (IRON CHAIN)
            double rawX = dx * Kp;
            double rawY = dy * Kp;

            if (rawX > 0) rawX = fmax(rawX, MIN_TENSION);
            else if (rawX < 0) rawX = fmin(rawX, -MIN_TENSION);
            
            if (rawY > 0) rawY = fmax(rawY, MIN_TENSION);
            else if (rawY < 0) rawY = fmin(rawY, -MIN_TENSION);

            double dampX = velX * Kd;
            double dampY = velY * Kd;
            
            state.forceX = clamp(rawX - dampX, -MAX_TENSION, MAX_TENSION);
            state.forceY = clamp(rawY - dampY, -MAX_TENSION, MAX_TENSION);
            
            state.locked = true;
        } else {
            prevTime = 0;
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
    
    BoneProjectionCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
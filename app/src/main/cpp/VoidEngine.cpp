#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <time.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_ESP", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class ESPBoneTracker {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    double velX = 0, velY = 0; 
    
    // [OMEGA IRON CHAIN]
    const double Kp = 3.0;             // Lực kéo mạnh hơn do ESP node rất nhỏ và chính xác
    const double Kd = 0.20;            
    const double MIN_TENSION = 150.0;  
    const double MAX_TENSION = 500.0;

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

        // 1. QUÉT MÀU CỦA ESP SKELETON (XUYÊN TƯỜNG)
        // Giả định Aimbody vẽ Node Đầu / Đường Xương bằng màu XANH LÁ NEON (R<80, G>200, B<80)
        // Hoặc màu VÀNG (R>200, G>200, B<50). Ở đây ta lọc Xanh Lá Neon.
        for (int y = 0; y < height; y += 3) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 0; x < width; x += 3) {
                const uint8_t* p = rowPtr + (x * 4);
                
                // Phát hiện màu Xanh Lá Neon của ESP (Hoặc chỉnh lại if theo màu ESP của anh)
                if (p[1] > 200 && p[0] < 100 && p[2] < 100) {
                    double weight = p[1]; // Độ sáng của màu xanh
                    sumWX += x * weight;
                    sumWY += y * weight;
                    totalWeight += weight;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                    pixelCount++;
                }
            }
        }

        // Chỉ cần 2 pixel ESP là đủ để khóa (Do đường kẻ xương rất mỏng)
        if (totalWeight > 0 && pixelCount >= 2) { 
            
            // Tọa độ tuyệt đối của Node Đầu (Head Node) do Aimbody vẽ ra
            double headNodeX = sumWX / totalWeight;
            double headNodeY = sumWY / totalWeight;

            // 2. ĐỒNG BỘ HÓA PHẦN CỨNG & VẬN TỐC
            double currentTime = getHardwareTime();
            double dt = currentTime - prevTime;
            if (dt < 0.001) dt = 0.001;

            if (prevTime > 0) {
                double rawVX = (headNodeX - prevCX) / dt;
                double rawVY = (headNodeY - prevCY) / dt;
                velX = (velX * 0.7) + (rawVX * 0.3);
                velY = (velY * 0.7) + (rawVY * 0.3);
            }
            prevCX = headNodeX; prevCY = headNodeY; prevTime = currentTime;

            // 3. DELTA TỪ TÂM MÀN HÌNH ĐẾN NODE XƯƠNG ESP
            double dx = headNodeX - (width / 2.0);
            double dy = headNodeY - (height / 2.0);

            // 4. SỢI XÍCH SẮT (IRON CHAIN)
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

        // Quét Máu (Infinity Sức - Vẫn quét UI của Game gốc)
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
    
    ESPBoneTracker core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
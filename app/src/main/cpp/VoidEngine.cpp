#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <time.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_ENTROPY", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class EntropyWeaponCore {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    double velX = 0, velY = 0; 
    
    // Trạng thái Mơ (Stochastic Inertia) khi mất dấu
    double dreamVelX = 0, dreamVelY = 0;
    int lostFrames = 0;

    // [OMEGA BIO-TREMOR] THÔNG SỐ NHỊP TIM & NHỊP THỞ
    const double HEARTBEAT_FREQ = 12.0;  // Nhịp tim giả lập
    const double BREATH_FREQ = 2.5;      // Nhịp thở giả lập
    const double TREMOR_AMP = 1.8;       // Biên độ rung sinh học (pixel)
    
    // [OMEGA IRON CHAIN]
    const double Kp = 2.8;             
    const double Kd = 0.18;            
    const double MIN_TENSION = 130.0;  
    const double MAX_TENSION = 450.0;

    double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    double getHardwareTime() {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return ts.tv_sec + ts.tv_nsec / 1e9;
    }

    // Hàm sinh ra Sự Hỗn Loạn Mượt Mà (Perlin-like Bio-Noise)
    double getBioNoise(double time, double seed) {
        return sin(time * HEARTBEAT_FREQ + seed) * 0.6 + 
               sin(time * BREATH_FREQ + seed * 1.3) * 0.4;
    }

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        
        double sumWX = 0, sumWY = 0, totalWeight = 0;
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        // Quét Khối (Torso/ESP Node)
        for (int y = 0; y < height; y += 4) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 0; x < width; x += 4) {
                const uint8_t* p = rowPtr + (x * 4);
                double redDom = p[0] - (p[1] + p[2]) * 0.5;
                // Quét Đỏ (Viền địch) hoặc Xanh Lá (ESP Node)
                if ((redDom > 40 && p[0] > 150) || (p[1] > 200 && p[0] < 100 && p[2] < 100)) {
                    double weight = p[0] + p[1]; 
                    sumWX += x * weight;
                    sumWY += y * weight;
                    totalWeight += weight;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                    pixelCount++;
                }
            }
        }

        double currentTime = getHardwareTime();
        double dt = currentTime - prevTime;
        if (dt < 0.001) dt = 0.001;

        if (totalWeight > 0 && pixelCount >= 2) { 
            double currentCX = sumWX / totalWeight;
            double currentCY = sumWY / totalWeight;
            
            // Cập nhật vận tốc thực
            if (prevTime > 0) {
                velX = (velX * 0.7) + (((currentCX - prevCX) / dt) * 0.3);
                velY = (velY * 0.7) + (((currentCY - prevCY) / dt) * 0.3);
            }
            prevCX = currentCX; prevCY = currentCY; prevTime = currentTime;
            lostFrames = 0;
            
            // Lưu vận tốc vào Trạng thái Mơ (Phòng khi địch núp)
            dreamVelX = velX;
            dreamVelY = velY;

            double dx = currentCX - (width / 2.0);
            double dy = currentCY - (height / 2.0);

            // [THIÊN TÀI 1: KÝ SINH TỪ TRƯỜNG (BULLET MAGNETISM HALO)]
            // Thêm Bio-Noise vào Delta. Tâm súng sẽ "thở" và "rung" quanh đầu địch.
            // Kích hoạt Aim-Assist ngầm của Free Fire hút đạn vào đầu.
            double noiseX = getBioNoise(currentTime, 1.0) * TREMOR_AMP;
            double noiseY = getBioNoise(currentTime, 5.0) * TREMOR_AMP;
            
            dx += noiseX;
            dy += noiseY;

            // Sợi Xích Sắt
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
            // [THIÊN TÀI 2: TRẠNG THÁI MƠ (STOCHASTIC INERTIA)]
            // Tọa độ không xác định (Mất dấu / Núp sau tường)
            lostFrames++;
            if (lostFrames < 30) { // Mơ trong 0.5 giây
                // Tự động trượt tâm theo quán tính ngẫu nhiên (Parabolic Drift)
                // Mô phỏng việc người chơi vẫn đang "đoán" và "lia" tâm theo hướng địch chạy
                double driftX = dreamVelX * 0.05;
                double driftY = dreamVelY * 0.05;
                
                // Thêm nhiễu ngẫu nhiên để Anti-Cheat không thấy đây là đường thẳng
                driftX += getBioNoise(currentTime, 9.0) * 2.0;
                driftY += getBioNoise(currentTime, 3.0) * 2.0;

                prevCX += driftX;
                prevCY += driftY;

                double dx = prevCX - (width / 2.0);
                double dy = prevCY - (height / 2.0);

                state.forceX = clamp(dx * Kp * 0.5, -MAX_TENSION, MAX_TENSION);
                state.forceY = clamp(dy * Kp * 0.5, -MAX_TENSION, MAX_TENSION);
                state.locked = true; // Vẫn giữ trạng thái "đang kéo" để Java không xả rác
            } else {
                state.forceX = 0; state.forceY = 0; state.locked = false;
            }
        }

        // Quét Máu
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
    
    EntropyWeaponCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
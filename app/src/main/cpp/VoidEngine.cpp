#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <time.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_FUNNEL", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class MagneticFunnelCore {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    double velX = 0, velY = 0; 
    
    // Trạng thái Mơ (Stochastic Inertia) khi mất dấu
    double dreamVelX = 0, dreamVelY = 0;
    int lostFrames = 0;

    // [OMEGA FUNNEL] THÔNG SỐ PHỄU HÚT & ĐÓNG BĂNG
    const double LOCK_ZONE_RADIUS = 2.5; // Bán kính đóng băng (pixel) - Vào đây là hàn chết
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
        int pixelCount = 0;

        // Quét Khối (Torso/ESP Node)
        for (int y = 0; y < height; y += 4) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 0; x < width; x += 4) {
                const uint8_t* p = rowPtr + (x * 4);
                double redDom = p[0] - (p[1] + p[2]) * 0.5;
                if ((redDom > 40 && p[0] > 150) || (p[1] > 200 && p[0] < 100 && p[2] < 100)) {
                    double weight = p[0] + p[1]; 
                    sumWX += x * weight;
                    sumWY += y * weight;
                    totalWeight += weight;
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
            
            if (prevTime > 0) {
                velX = (velX * 0.7) + (((currentCX - prevCX) / dt) * 0.3);
                velY = (velY * 0.7) + (((currentCY - prevCY) / dt) * 0.3);
            }
            prevCX = currentCX; prevCY = currentCY; prevTime = currentTime;
            lostFrames = 0;
            dreamVelX = velX; dreamVelY = velY;

            double dx = currentCX - (width / 2.0);
            double dy = currentCY - (height / 2.0);
            double dist = std::sqrt(dx*dx + dy*dy);

            // [THIÊN TÀI 1: VÙNG ĐÓNG BĂNG TUYỆT ĐỐI (ABSOLUTE LOCK ZONE)]
            // Khi tâm súng lọt vào bán kính 2.5px quanh đầu địch -> CẮT ĐỨT LỰC KÉO
            // Touch-DAC sẽ ngưng bơm xung. Tâm súng ĐÓNG BĂNG, không một gợn sóng, không lung lay.
            if (dist < LOCK_ZONE_RADIUS) {
                state.forceX = 0;
                state.forceY = 0;
                state.locked = true;
                return state;
            }

            // [THIÊN TÀI 2: PHỄU HÚT TỪ TÍNH (MAGNETIC FUNNEL - ĐẨY NHẸ LÀ LÊN)]
            // Càng gần đầu, lực hút (Kp) càng tăng theo hàm nghịch đảo (Hố đen)
            // Thêm Velocity Assist (Hỗ trợ vận tốc) để tâm súng "lướt" theo hướng người dùng đẩy
            double dynamicKp = 2.0 + (150.0 / (dist + 5.0)); 
            double dynamicKd = 0.8 + (dist * 0.02); // Lực hãm tăng dần để không bị trượt qua đầu
            
            double assistX = velX * 0.15; // Lướt trên màn hình
            double assistY = velY * 0.15;

            double rawX = (dx * dynamicKp) + assistX;
            double rawY = (dy * dynamicKp) + assistY;

            double dampX = velX * dynamicKd;
            double dampY = velY * dynamicKd;
            
            state.forceX = clamp(rawX - dampX, -MAX_TENSION, MAX_TENSION);
            state.forceY = clamp(rawY - dampY, -MAX_TENSION, MAX_TENSION);
            state.locked = true;

        } else {
            // [THIÊN TÀI 3: TRẠNG THÁI MƠ (STOCHASTIC INERTIA)]
            lostFrames++;
            if (lostFrames < 30) { 
                double driftX = dreamVelX * 0.05;
                double driftY = dreamVelY * 0.05;
                prevCX += driftX;
                prevCY += driftY;

                double dx = prevCX - (width / 2.0);
                double dy = prevCY - (height / 2.0);

                state.forceX = clamp(dx * 1.5, -MAX_TENSION, MAX_TENSION);
                state.forceY = clamp(dy * 1.5, -MAX_TENSION, MAX_TENSION);
                state.locked = true; 
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
    
    MagneticFunnelCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <time.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_IK", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class InverseKinematicsCore {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    double velX = 0, velY = 0; 
    
    double dreamVelX = 0, dreamVelY = 0;
    int lostFrames = 0;

    // [OMEGA JSON WEAPONIZED] TỶ LỆ VÀNG RÚT TRÍCH TỪ FILE JSON
    // Torso(100) -> Head(20) = 80. Root(200) -> Head(20) = 180. Ratio = 80/180
    const double JSON_HEAD_PROJECTION_RATIO = 0.444; 
    
    // [OMEGA FUNNEL & FREEZE]
    const double LOCK_ZONE_RADIUS = 2.5; // Bán kính đóng băng tuyệt đối
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

        // 1. QUÉT KHỐI THÂN (TORSO MASS) - BỎ QUA ĐẦU NẾU NÓ QUÁ NHỎ / BỊ KHUẤT
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
            // Tọa độ Tâm Khối (Torso Center)
            double torsoCX = sumWX / totalWeight;
            double torsoCY = sumWY / totalWeight;
            
            int boxHeight = maxY - minY;
            
            // 2. CHIẾU NGƯỢC KHUNG XƯƠNG (JSON INVERSE PROJECTION)
            // Dùng Tỷ Lệ Vàng 0.444 để suy luận ra tọa độ "Đầu Ảo" (Phantom Head)
            // Dù đầu thật đang núp sau tường, Đầu Ảo vẫn được tính toán chính xác trên không trung
            double phantomHeadX = torsoCX;
            double phantomHeadY = torsoCY - (boxHeight * JSON_HEAD_PROJECTION_RATIO);

            if (prevTime > 0) {
                velX = (velX * 0.7) + (((phantomHeadX - prevCX) / dt) * 0.3);
                velY = (velY * 0.7) + (((phantomHeadY - prevCY) / dt) * 0.3);
            }
            prevCX = phantomHeadX; prevCY = phantomHeadY; prevTime = currentTime;
            lostFrames = 0;
            dreamVelX = velX; dreamVelY = velY;

            double dx = phantomHeadX - (width / 2.0);
            double dy = phantomHeadY - (height / 2.0);
            double dist = std::sqrt(dx*dx + dy*dy);

            // 3. ĐÓNG BĂNG TUYỆT ĐỐI (ABSOLUTE FREEZE)
            if (dist < LOCK_ZONE_RADIUS) {
                state.forceX = 0;
                state.forceY = 0;
                state.locked = true;
                return state;
            }

            // 4. PHỄU HÚT TỪ TÍNH (MAGNETIC FUNNEL - ĐẨY NHẸ LÀ LÊN)
            double dynamicKp = 2.0 + (150.0 / (dist + 5.0)); 
            double dynamicKd = 0.8 + (dist * 0.02); 
            
            double assistX = velX * 0.15; 
            double assistY = velY * 0.15;

            double rawX = (dx * dynamicKp) + assistX;
            double rawY = (dy * dynamicKp) + assistY;

            double dampX = velX * dynamicKd;
            double dampY = velY * dynamicKd;
            
            state.forceX = clamp(rawX - dampX, -MAX_TENSION, MAX_TENSION);
            state.forceY = clamp(rawY - dampY, -MAX_TENSION, MAX_TENSION);
            state.locked = true;

        } else {
            // 5. TRẠNG THÁI MƠ (STOCHASTIC INERTIA - BYPASS MẤT DẤU)
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
    
    InverseKinematicsCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
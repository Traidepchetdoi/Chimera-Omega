#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <algorithm> // [OMEGA STL PATCH] Triệu hồi std::max và std::min cho Integer
#include <time.h>
#include <atomic>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_GRID", __VA_ARGS__)

// ... (Toàn bộ phần code bên dưới giữ nguyên 100%, không cần sửa bất kỳ chữ nào) ...

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

std::atomic<float> g_gyroX{0.0f};
std::atomic<float> g_gyroY{0.0f};

class AdaptiveGridCore {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    double velX = 0, velY = 0; 
    
    double dreamVelX = 0, dreamVelY = 0;
    int lostFrames = 0;

    const double JSON_HEAD_PROJECTION_RATIO = 0.444; 
    const double LOCK_ZONE_RADIUS = 3.0; 
    const double MAX_TENSION = 600.0;
    
    const double FRICTION_MULTIPLIER = 5.0; 
    const double GYRO_THRESHOLD = 0.12;     

    double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    double getHardwareTime() {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return ts.tv_sec + ts.tv_nsec / 1e9;
    }

    inline bool isTargetPixel(const uint8_t* p) {
        double redDom = p[0] - (p[1] + p[2]) * 0.5;
        return (redDom > 35 && p[0] > 140) || (p[1] > 180 && p[0] < 110 && p[2] < 110);
    }

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        
        // [ADAPTIVE GRID] PASS 1: QUÉT THÔ TÌM VÙNG NGHI NGỜ (ROI)
        int roiMinX = width, roiMaxX = 0, roiMinY = height, roiMaxY = 0;
        bool foundROI = false;

        for (int y = 0; y < height; y += 4) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 0; x < width; x += 4) {
                if (isTargetPixel(rowPtr + (x * 4))) {
                    if (x < roiMinX) roiMinX = x; if (x > roiMaxX) roiMaxX = x;
                    if (y < roiMinY) roiMinY = y; if (y > roiMaxY) roiMaxY = y;
                    foundROI = true;
                }
            }
        }

        double sumWX = 0, sumWY = 0, totalWeight = 0;
        int pixelCount = 0;
        int finalMinX = width, finalMaxX = 0, finalMinY = height, finalMaxY = 0;

        if (foundROI) {
            // Mở rộng ROI ra 15px để đảm bảo không cắt mất viền mờ
            int scanMinX = std::max(0, roiMinX - 15);
            int scanMaxX = std::min(width - 1, roiMaxX + 15);
            int scanMinY = std::max(0, roiMinY - 15);
            int scanMaxY = std::min(height - 1, roiMaxY + 15);

            // [ADAPTIVE GRID] PASS 2: QUÉT TINH TỪNG 1 PIXEL (BẮT CHẤM 1PX Ở 400M)
            for (int y = scanMinY; y <= scanMaxY; y++) {
                const uint8_t* rowPtr = basePtr + (y * rowStride);
                for (int x = scanMinX; x <= scanMaxX; x++) {
                    const uint8_t* p = rowPtr + (x * 4);
                    if (isTargetPixel(p)) {
                        double weight = p[0] + p[1]; 
                        sumWX += x * weight;
                        sumWY += y * weight;
                        totalWeight += weight;
                        if (x < finalMinX) finalMinX = x; if (x > finalMaxX) finalMaxX = x;
                        if (y < finalMinY) finalMinY = y; if (y > finalMaxY) finalMaxY = y;
                        pixelCount++;
                    }
                }
            }
        }

        double currentTime = getHardwareTime();
        double dt = currentTime - prevTime;
        if (dt < 0.001) dt = 0.001;

        float gyroX = g_gyroX.load();
        float gyroY = g_gyroY.load();
        double gyroMagnitude = std::sqrt(gyroX * gyroX + gyroY * gyroY);
        bool isUserSwiping = (gyroMagnitude > GYRO_THRESHOLD);

        // Chỉ cần 1 pixel tinh cũng đủ để khóa (pixelCount >= 1)
        if (totalWeight > 0 && pixelCount >= 1) { 
            double torsoCX = sumWX / totalWeight;
            double torsoCY = sumWY / totalWeight;
            int boxHeight = finalMaxY - finalMinY;
            if (boxHeight < 2) boxHeight = 2; // Chặn chia cho 0 ở tầm cực xa
            
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

            if (dist < LOCK_ZONE_RADIUS && !isUserSwiping) {
                state.forceX = 0; state.forceY = 0; state.locked = true;
                return state;
            }

            double dynamicKp = 2.5 + (200.0 / (dist + 5.0)); 
            double dynamicKd = 1.0 + (dist * 0.02); 
            
            if (isUserSwiping && dist < 120.0) {
                dynamicKd *= FRICTION_MULTIPLIER;
                dynamicKp *= 1.8; 
            }
            
            double assistX = velX * 0.20; 
            double assistY = velY * 0.20;

            double rawX = (dx * dynamicKp) + assistX;
            double rawY = (dy * dynamicKp) + assistY;

            double dampX = velX * dynamicKd;
            double dampY = velY * dynamicKd;
            
            state.forceX = clamp(rawX - dampX, -MAX_TENSION, MAX_TENSION);
            state.forceY = clamp(rawY - dampY, -MAX_TENSION, MAX_TENSION);
            state.locked = true;

        } else {
            lostFrames++;
            if (lostFrames < 45) { // Mơ lâu hơn (0.75s) do tầm xa địch hay khuất
                double driftX = dreamVelX * 0.08;
                double driftY = dreamVelY * 0.08;
                prevCX += driftX; prevCY += driftY;

                double dx = prevCX - (width / 2.0);
                double dy = prevCY - (height / 2.0);

                state.forceX = clamp(dx * 2.0, -MAX_TENSION, MAX_TENSION);
                state.forceY = clamp(dy * 2.0, -MAX_TENSION, MAX_TENSION);
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

extern "C" JNIEXPORT void JNICALL
Java_com_omega_host_OpticalPhantomService_updateGyroVector(JNIEnv* env, jobject thiz, jfloat rx, jfloat ry) {
    g_gyroX.store(rx); g_gyroY.store(ry);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_omega_host_OpticalPhantomService_processOpticalFrame(
    JNIEnv* env, jobject thiz, jobject byteBuffer, jint w, jint h, jint rowStride) {
    
    uint8_t* basePtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!basePtr) return nullptr;
    
    AdaptiveGridCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
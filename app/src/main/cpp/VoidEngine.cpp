#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <algorithm>
#include <time.h>
#include <atomic>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_INFINITE", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

std::atomic<float> g_gyroX{0.0f};
std::atomic<float> g_gyroY{0.0f};

class InfinitePowerCore {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    
    double ghostX = 0, ghostY = 0;
    double ghostVX = 0, ghostVY = 0;
    bool wasLocked = false;
    int ghostFrames = 0;

    const double JSON_HEAD_PROJECTION_RATIO = 0.444; 
    
    // [OMEGA INFINITE POWER CURVE] THÔNG SỐ ĐƯỜNG CONG VÔ HẠN
    const double K_FACTOR = 0.025;       // Hệ số vi mô (Tạo ra 0.01 ở cự ly sát)
    const double EXPONENT = 1.85;        // Số mũ vô hạn (Càng xa, gia tốc càng kinh khủng)
    const double MAX_180_TURN = 1500.0;  // Giới hạn vật lý của 1 cú xoay 180 độ (Chống xoay trực thăng)
    
    const double GYRO_THRESHOLD = 0.12;     
    const double CANCELLATION_RATIO = 12.0; 

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
            int scanMinX = std::max(0, roiMinX - 15);
            int scanMaxX = std::min(width - 1, roiMaxX + 15);
            int scanMinY = std::max(0, roiMinY - 15);
            int scanMaxY = std::min(height - 1, roiMaxY + 15);

            for (int y = scanMinY; y <= scanMaxY; y++) {
                const uint8_t* rowPtr = basePtr + (y * rowStride);
                for (int x = scanMinX; x <= scanMaxX; x++) {
                    const uint8_t* p = rowPtr + (x * 4);
                    if (isTargetPixel(p)) {
                        double weight = p[0] + p[1]; 
                        sumWX += x * weight; sumWY += y * weight; totalWeight += weight;
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

        double targetX = 0, targetY = 0;
        bool hasTarget = false;

        if (totalWeight > 0 && pixelCount >= 1) { 
            double torsoCX = sumWX / totalWeight;
            double torsoCY = sumWY / totalWeight;
            int boxHeight = finalMaxY - finalMinY;
            if (boxHeight < 2) boxHeight = 2; 
            
            targetX = torsoCX;
            targetY = torsoCY - (boxHeight * JSON_HEAD_PROJECTION_RATIO);
            hasTarget = true;
            wasLocked = true;
            ghostFrames = 0;

            if (prevTime > 0) {
                ghostVX = (targetX - prevCX) / dt;
                ghostVY = (targetY - prevCY) / dt;
            }
            ghostX = targetX; ghostY = targetY;

        } else {
            if (wasLocked) {
                ghostFrames++;
                if (ghostFrames < 120) { 
                    ghostX += ghostVX * dt;
                    ghostY += ghostVY * dt;
                    ghostVX *= 0.98; 
                    ghostVY *= 0.98; 
                    targetX = ghostX;
                    targetY = ghostY;
                    hasTarget = true; 
                } else {
                    wasLocked = false;
                }
            }
        }

        prevCX = targetX; prevCY = targetY; prevTime = currentTime;

        if (hasTarget) {
            double dx = targetX - (width / 2.0);
            double dy = targetY - (height / 2.0);
            double dist = std::sqrt(dx*dx + dy*dy);
            if (dist < 0.5) dist = 0.5; 

            // [OMEGA INFINITE POWER CURVE] HÀM LŨY THỪA VÔ HẠN
            // Không có trần 1.0. Hệ số nhân tăng theo cấp số nhân (2x, 5x, 15x...)
            double swipe_magnitude = K_FACTOR * std::pow(dist, EXPONENT);
            
            // Bảo vệ Unity Engine: Kẹp kết quả vật lý ở mức 180 độ (1500px)
            if (swipe_magnitude > MAX_180_TURN) swipe_magnitude = MAX_180_TURN;

            // Chuẩn hóa Vector (Giữ nguyên hướng, áp dụng độ lớn vô hạn)
            double norm_dx = dx / dist;
            double norm_dy = dy / dist;

            double raw_swipeX = norm_dx * swipe_magnitude;
            double raw_swipeY = norm_dy * swipe_magnitude;

            // Triệt tiêu xung lượng tay người dùng
            if (gyroMagnitude > GYRO_THRESHOLD) {
                raw_swipeX -= (gyroX * CANCELLATION_RATIO);
                raw_swipeY -= (gyroY * CANCELLATION_RATIO);
            }

            state.forceX = raw_swipeX;
            state.forceY = raw_swipeY;
            state.locked = true;

        } else {
            state.forceX = 0; state.forceY = 0; state.locked = false;
        }

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
    
    InfinitePowerCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
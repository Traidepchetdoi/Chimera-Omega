#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <algorithm>
#include <time.h>
#include <atomic>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_FLAT_WARP", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    double dist; // Truyền khoảng cách xuống Java để làm Haptic Geiger
    bool locked;
    bool needsHeal;
};

std::atomic<float> g_gyroX{0.0f};
std::atomic<float> g_gyroY{0.0f};

class FlatWarpCore {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    double ghostX = 0, ghostY = 0, ghostVX = 0, ghostVY = 0;
    bool wasLocked = false;
    int ghostFrames = 0;

    const double JSON_HEAD_PROJECTION_RATIO = 0.444; 
    const double MAX_WARP_PX = 800.0;      // Tốc độ dịch chuyển phẳng (Vô hạn nhưng an toàn cho Unity)
    const double SCOPE_MULTIPLIER = 3.5;   // Bù trừ độ nhạy khi bật ống ngắm
    const double FREEZE_ZONE = 1.5;        // Vùng đóng băng tuyệt đối

    double getHardwareTime() {
        struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
        return ts.tv_sec + ts.tv_nsec / 1e9;
    }

    inline bool isTargetPixel(const uint8_t* p) {
        double redDom = p[0] - (p[1] + p[2]) * 0.5;
        return (redDom > 35 && p[0] > 140) || (p[1] > 180 && p[0] < 110 && p[2] < 110);
    }

    // Phát hiện ống ngắm qua viền tối 4 góc
    bool detectScope(const uint8_t* basePtr, int width, int height, int rowStride) {
        int blackCorners = 0;
        int points[8] = {10, 10, width-10, 10, 10, height-10, width-10, height-10};
        for(int i=0; i<8; i+=2) {
            int x = points[i], y = points[i+1];
            if(x>=0 && x<width && y>=0 && y<height) {
                const uint8_t* p = basePtr + (y * rowStride) + (x * 4);
                if(p[0] < 15 && p[1] < 15 && p[2] < 15) blackCorners++;
            }
        }
        return (blackCorners >= 3); // Nếu 3/4 góc tối đen -> Đang bật ống ngắm
    }

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, 0, false, false};
        
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
            int scanMinX = std::max(0, roiMinX - 15), scanMaxX = std::min(width - 1, roiMaxX + 15);
            int scanMinY = std::max(0, roiMinY - 15), scanMaxY = std::min(height - 1, roiMaxY + 15);

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

        double targetX = 0, targetY = 0;
        bool hasTarget = false;

        if (totalWeight > 0 && pixelCount >= 1) { 
            double torsoCX = sumWX / totalWeight;
            double torsoCY = sumWY / totalWeight;
            int boxHeight = finalMaxY - finalMinY;
            if (boxHeight < 2) boxHeight = 2; 
            
            targetX = torsoCX;
            targetY = torsoCY - (boxHeight * JSON_HEAD_PROJECTION_RATIO);
            hasTarget = true; wasLocked = true; ghostFrames = 0;

            if (prevTime > 0) {
                ghostVX = (targetX - prevCX) / dt; ghostVY = (targetY - prevCY) / dt;
            }
            ghostX = targetX; ghostY = targetY;
        } else {
            if (wasLocked) {
                ghostFrames++;
                if (ghostFrames < 120) { 
                    ghostX += ghostVX * dt; ghostY += ghostVY * dt;
                    ghostVX *= 0.98; ghostVY *= 0.98; 
                    targetX = ghostX; targetY = ghostY; hasTarget = true; 
                } else { wasLocked = false; }
            }
        }
        prevCX = targetX; prevCY = targetY; prevTime = currentTime;

        if (hasTarget) {
            double dx = targetX - (width / 2.0);
            double dy = targetY - (height / 2.0);
            double dist = std::sqrt(dx*dx + dy*dy);
            state.dist = dist;

            // [OMEGA FLAT-WARP] DỊCH CHUYỂN PHẲNG VÔ HẠN
            double swipeX = 0, swipeY = 0;
            if (dist > FREEZE_ZONE) {
                double warpDist = std::min(dist, MAX_WARP_PX);
                swipeX = (dx / dist) * warpDist;
                swipeY = (dy / dist) * warpDist;
            } else {
                swipeX = 0; swipeY = 0; // ĐÓNG BĂNG TUYỆT ĐỐI
            }

            // [OMEGA SCOPE COMPENSATION] BÙ TRỪ ỐNG NGĂM
            if (detectScope(basePtr, width, height, rowStride)) {
                swipeX *= SCOPE_MULTIPLIER;
                swipeY *= SCOPE_MULTIPLIER;
            }

            // Triệt tiêu xung lượng tay
            float gyroX = g_gyroX.load(); float gyroY = g_gyroY.load();
            double gyroMag = std::sqrt(gyroX*gyroX + gyroY*gyroY);
            if (gyroMag > 0.12) {
                swipeX -= (gyroX * 10.0);
                swipeY -= (gyroY * 10.0);
            }

            state.forceX = swipeX;
            state.forceY = swipeY;
            state.locked = true;
        } else {
            state.forceX = 0; state.forceY = 0; state.dist = 0; state.locked = false;
        }

        // Quét Máu
        int healthPixels = 0, totalHealthPixels = 0;
        int barStartY = height - 150, barEndY = height - 100;
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
    FlatWarpCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    jdoubleArray result = env->NewDoubleArray(5);
    double data[5] = {state.forceX, state.forceY, state.dist, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 5, data);
    return result;
}
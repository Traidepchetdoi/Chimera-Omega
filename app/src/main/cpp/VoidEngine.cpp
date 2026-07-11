#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <time.h>
#include <atomic>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_GHOST", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

std::atomic<float> g_gyroX{0.0f};
std::atomic<float> g_gyroY{0.0f};

class GhostCancellationCore {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    
    // [OMEGA GHOST] BIẾN NHỚ BÓNG MA (DÙNG KHI GAME ENGINE TẮT VIỀN ĐỎ Ở 200M)
    double ghostX = 0, ghostY = 0;
    double ghostVX = 0, ghostVY = 0;
    bool wasLocked = false;
    int ghostFrames = 0;

    const double JSON_HEAD_PROJECTION_RATIO = 0.444; 
    
    // [OMEGA IMPULSE CANCELLATION] HỆ SỐ TRIỆT TIÊU XUNG LƯỢNG
    // Không có MAX_TENSION. Lực cản = Lực vuốt của người dùng * 15.0
    const double CANCELLATION_RATIO = 15.0; 
    const double BASE_LOCK_TENSION = 250.0; // Lực hút nền khi đứng yên
    const double GYRO_THRESHOLD = 0.10;     

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
        
        // 1. QUÉT THỰC THỂ (ADAPTIVE GRID)
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

            // Lưu vận tốc vào Bóng Ma (Để chuẩn bị cho lúc mất dấu ở 200m)
            if (prevTime > 0) {
                ghostVX = (targetX - prevCX) / dt;
                ghostVY = (targetY - prevCY) / dt;
            }
            ghostX = targetX; ghostY = targetY;

        } else {
            // [OMEGA GHOST TRACKING] KÍCH HOẠT BÓNG MA KHI MẤT DẤU (200M / NÚP SAU TƯỜNG)
            if (wasLocked) {
                ghostFrames++;
                if (ghostFrames < 120) { // Nuôi Bóng Ma trong 2 giây
                    // Dead Reckoning: Bóng ma tiếp tục bay theo quán tính
                    ghostX += ghostVX * dt;
                    ghostY += ghostVY * dt;
                    
                    // Giả lập ma sát không khí
                    ghostVX *= 0.98; 
                    ghostVY *= 0.98; 
                    
                    targetX = ghostX;
                    targetY = ghostY;
                    hasTarget = true; // Vẫn báo là đang khóa (để Java tiếp tục bơm lực cản)
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

            // [OMEGA IMPULSE CANCELLATION] TRIỆT TIÊU XUNG LƯỢNG CHỦ ĐỘNG
            // Lực hút nền (Khi anh không vuốt)
            double dynamicTension = BASE_LOCK_TENSION; 
            
            // Nếu anh đang cố tình vuốt MẠNH để thoát khỏi đầu địch (Gyro lớn)
            if (gyroMagnitude > GYRO_THRESHOLD) {
                // Lực cản = Lực vuốt của anh * 15.0. 
                // Anh vuốt càng mạnh, Bãi Lầy Nam Châm càng hút ngược lại vô cực.
                dynamicTension += (gyroMagnitude * CANCELLATION_RATIO * 100.0); 
            }

            // Tính toán Vector kéo
            double pullX = dx * (dynamicTension / (dist + 1.0));
            double pullY = dy * (dynamicTension / (dist + 1.0));

            // Giới hạn lại ở mức an toàn cho Touch-DAC (Tránh xé rách Input Dispatcher)
            // Nhưng vẫn đủ lớn để đè bẹp lực vuốt của ngón tay
            state.forceX = clamp(pullX, -800.0, 800.0);
            state.forceY = clamp(pullY, -800.0, 800.0);
            state.locked = true;

        } else {
            state.forceX = 0; state.forceY = 0; state.locked = false;
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
    
    GhostCancellationCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
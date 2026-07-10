#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_PID", __VA_ARGS__)

struct TargetState {
    float forceX, forceY; // Lực đã được PID tính toán (Có cả lực hãm)
    bool locked;
    bool needsHeal;
};

class PIDCore {
private:
    float prevDX = 0, prevDY = 0;
    
    // [OMEGA PID] THÔNG SỐ ĐIỀU KHIỂN
    const float Kp = 0.12f;  // Lực kéo (Proportional)
    const float Kd = 0.85f;  // Lực hãm (Derivative - Chống Overshoot)

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        // [FULL-SCREEN FOV] Quét TOÀN BỘ MÀN HÌNH (Nhảy cóc 4 pixel để bù hiệu năng)
        for (int y = 0; y < height; y += 4) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 0; x < width; x += 4) {
                const uint8_t* p = rowPtr + (x * 4);
                if (p[0] > 180 && p[1] < 90 && p[2] < 90) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                    pixelCount++;
                }
            }
        }

        if (pixelCount > 30 && maxX > minX && maxY > minY) {
            int bw = maxX - minX;
            int bh = maxY - minY;
            if (bh > bw * 0.5f) {
                float headX = minX + (bw / 2.0f);
                float headY = minY + (bh * 0.22f); 
                
                // Delta (Khoảng cách từ tâm màn hình đến đầu địch)
                float dx = headX - (width / 2.0f);
                float dy = headY - (height / 2.0f);

                // [PID CONTROLLER] Tính toán Lực Kéo & Lực Hãm
                // P = Lực kéo thuận
                float pX = dx * Kp;
                float pY = dy * Kp;
                
                // D = Lực hãm (Dựa trên vận tốc lao vào đầu địch)
                float dX = (dx - prevDX) * Kd;
                float dY = (dy - prevDY) * Kd;
                
                // Tổng lực (Sẽ tự động giảm về 0 khi tiến sát đầu địch)
                state.forceX = pX - dX; 
                state.forceY = pY - dY;
                
                prevDX = dx;
                prevDY = dy;
                state.locked = true;
            }
        } else {
            prevDX = 0; prevDY = 0; // Reset khi mất dấu
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
        if (totalHealthPixels > 0 && ((float)healthPixels / totalHealthPixels) < 0.35f) state.needsHeal = true;

        return state;
    }
};

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_omega_host_OpticalPhantomService_processOpticalFrame(
    JNIEnv* env, jobject thiz, jobject byteBuffer, jint w, jint h, jint rowStride) {
    
    uint8_t* basePtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!basePtr) return nullptr;
    
    PIDCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jfloatArray result = env->NewFloatArray(4);
    float data[4] = {state.forceX, state.forceY, state.locked ? 1.0f : 0.0f, state.needsHeal ? 1.0f : 0.0f};
    env->SetFloatArrayRegion(result, 0, 4, data);
    
    return result;
}
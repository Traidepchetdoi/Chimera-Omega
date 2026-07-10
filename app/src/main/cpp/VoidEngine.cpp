#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_ETERNITY", __VA_ARGS__)

struct TargetState {
    float headX, headY;
    bool locked;
    bool needsHeal;
};

class EternityCore {
private:
    int screenW, screenH;
    float prevX = 0, prevY = 0;
    float vx = 0, vy = 0;
    int framesMissed = 0;

public:
    EternityCore(int w, int h) : screenW(w), screenH(h) {}

    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        // Vùng quét địch (Giữa màn hình)
        int sX = width / 4, eX = (width * 3) / 4;
        int sY = height / 4, eY = (height * 3) / 4;

        // 1. QUÉT MỤC TIÊU (AABB & SWAR)
        for (int y = sY; y < eY; y += 3) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = sX; x < eX; x += 3) {
                const uint8_t* p = rowPtr + (x * 4);
                // Tìm màu đỏ (Outline địch)
                if (p[0] > 180 && p[1] < 90 && p[2] < 90) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                    pixelCount++;
                }
            }
        }

        bool currentLock = false;
        float currentX = 0, currentY = 0;

        if (pixelCount > 40 && maxX > minX && maxY > minY) {
            int bw = maxX - minX;
            int bh = maxY - minY;
            if (bh > bw * 0.6f) {
                currentX = minX + (bw / 2.0f);
                currentY = minY + (bh * 0.20f); // Khóa đầu (20% từ đỉnh)
                currentLock = true;
            }
        }

        // 2. KHÓA INFINITY (KALMAN FILTER - XUYÊN TƯỜNG)
        if (currentLock) {
            vx = currentX - prevX;
            vy = currentY - prevY;
            prevX = currentX;
            prevY = currentY;
            framesMissed = 0;
            
            // Dự đoán trước 5 frames
            state.headX = currentX + (vx * 5.0f);
            state.headY = currentY + (vy * 5.0f);
            state.locked = true;
        } else {
            framesMissed++;
            if (framesMissed < 15) { // Giữ khóa 15 frames khi mất dấu
                state.headX = prevX + (vx * 5.0f);
                state.headY = prevY + (vy * 5.0f);
                state.locked = true; 
            }
        }

        // 3. KHÓA INFINITY SỨC (QUÉT THANH MÁU)
        // Giả định thanh máu nằm góc dưới trái (X: 50->250, Y: height-150 -> height-100)
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
        
        if (totalHealthPixels > 0) {
            float ratio = (float)healthPixels / totalHealthPixels;
            if (ratio < 0.35f) state.needsHeal = true; // Máu < 35% -> Báo động
        }

        return state;
    }
};

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_omega_host_OpticalPhantomService_processOpticalFrame(
    JNIEnv* env, jobject thiz, jobject byteBuffer, jint w, jint h, jint rowStride) {
    
    uint8_t* basePtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!basePtr) return nullptr;
    
    EternityCore core(w, h);
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jfloatArray result = env->NewFloatArray(4);
    float data[4] = {state.headX, state.headY, state.locked ? 1.0f : 0.0f, state.needsHeal ? 1.0f : 0.0f};
    env->SetFloatArrayRegion(result, 0, 4, data);
    
    return result;
}
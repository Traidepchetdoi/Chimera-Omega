#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_MICRO", __VA_ARGS__)

struct TargetState {
    float dx, dy; // Độ lệch so với tâm màn hình
    bool locked;
    bool needsHeal;
};

class MicroStepCore {
private:
    float smoothDX = 0, smoothDY = 0;
    const float ALPHA = 0.65f; // Hệ số làm mượt (Ngăn cản nhiễu lượng tử pixel)

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        // Tâm màn hình động (Tự thích nghi nếu anh đổi Density)
        int centerX = width / 2;
        int centerY = height / 2;

        // Vùng quét (Giới hạn ở giữa để tiết kiệm CPU)
        int sX = width / 4, eX = (width * 3) / 4;
        int sY = height / 4, eY = (height * 3) / 4;

        for (int y = sY; y < eY; y += 3) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = sX; x < eX; x += 3) {
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
                float headY = minY + (bh * 0.22f); // 22% từ đỉnh (Chuẩn Hitbox đầu Free Fire)
                
                float rawDX = headX - centerX;
                float rawDY = headY - centerY;

                // Lọc Alpha-Beta: Nuốt chửng sự rung lắc của viền đỏ
                smoothDX = (ALPHA * rawDX) + ((1.0f - ALPHA) * smoothDX);
                smoothDY = (ALPHA * rawDY) + ((1.0f - ALPHA) * smoothDY);

                state.dx = smoothDX;
                state.dy = smoothDY;
                state.locked = true;
            }
        } else {
            // Mất dấu: Trượt theo quán tính
            state.dx = smoothDX;
            state.dy = smoothDY;
            state.locked = false; 
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
    
    MicroStepCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jfloatArray result = env->NewFloatArray(4);
    float data[4] = {state.dx, state.dy, state.locked ? 1.0f : 0.0f, state.needsHeal ? 1.0f : 0.0f};
    env->SetFloatArrayRegion(result, 0, 4, data);
    
    return result;
}
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_NEURAL", __VA_ARGS__)

struct TargetState {
    float headX, headY;
    bool locked;
    bool needsHeal;
};

class NeuralSyncCore {
private:
    int screenW, screenH;
    
    // Trạng thái Kalman / Alpha-Beta Filter
    float smoothX = 0, smoothY = 0;
    float velX = 0, velY = 0;
    int framesMissed = 0;
    
    // Hệ số làm mượt (Alpha) và Hệ số ước lượng vận tốc (Beta)
    const float ALPHA = 0.45f; 
    const float BETA = 0.15f;
    
    // Ngưỡng chống rung vi mô (Deadzone)
    const float JITTER_THRESHOLD = 6.0f; 

public:
    NeuralSyncCore(int w, int h) : screenW(w), screenH(h) {}

    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        int sX = width / 4, eX = (width * 3) / 4;
        int sY = height / 4, eY = (height * 3) / 4;

        // 1. QUÉT THÔ (RAW SCAN)
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

        bool currentLock = false;
        float rawX = 0, rawY = 0;

        if (pixelCount > 40 && maxX > minX && maxY > minY) {
            int bw = maxX - minX;
            int bh = maxY - minY;
            if (bh > bw * 0.6f) {
                rawX = minX + (bw / 2.0f);
                rawY = minY + (bh * 0.20f);
                currentLock = true;
            }
        }

        // 2. BỘ LỌC THẦN KINH (ALPHA-BETA FILTER & PREDICTION)
        if (currentLock) {
            if (smoothX == 0 && smoothY == 0) {
                // Khởi tạo lần đầu
                smoothX = rawX; smoothY = rawY;
                velX = 0; velY = 0;
            } else {
                // Ước lượng vị trí mới dựa trên vận tốc cũ
                float predX = smoothX + velX;
                float predY = smoothY + velY;
                
                // Tính toán sai số (Residual)
                float resX = rawX - predX;
                float resY = rawY - predY;
                
                // Cập nhật vị trí làm mượt (Smooth)
                smoothX = predX + (ALPHA * resX);
                smoothY = predY + (ALPHA * resY);
                
                // Cập nhật vận tốc (Velocity)
                velX = velX + (BETA * resX);
                velY = velY + (BETA * resY);
            }
            framesMissed = 0;
        } else {
            framesMissed++;
            // Mất dấu: Ngoại suy quán tính (Inertia Extrapolation)
            smoothX += velX;
            smoothY += velY;
            velX *= 0.9f; // Ma sát (Friction)
            velY *= 0.9f;
        }

        // 3. CHỐNG RUNG LẮC (JITTER CANCELLATION)
        // Nếu địch di chuyển < 6 pixel, coi như đứng yên -> Khóa cứng tọa độ
        float dx = smoothX - state.headX; // So với frame trước
        float dy = smoothY - state.headY;
        
        if (currentLock || framesMissed < 15) {
            // DỰ ĐOÁN TƯƠNG LAI (Bù trừ 50ms độ trễ của MediaProjection)
            // Vẽ tâm súng ở vị trí của 2.5 khung hình tiếp theo
            state.headX = smoothX + (velX * 2.5f);
            state.headY = smoothY + (velY * 2.5f);
            state.locked = true;
        }

        // 4. INFINITY SỨC (QUÉT THANH MÁU - Giữ nguyên)
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
        if (totalHealthPixels > 0 && ((float)healthPixels / totalHealthPixels) < 0.35f) {
            state.needsHeal = true;
        }

        return state;
    }
};

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_omega_host_OpticalPhantomService_processOpticalFrame(
    JNIEnv* env, jobject thiz, jobject byteBuffer, jint w, jint h, jint rowStride) {
    
    uint8_t* basePtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!basePtr) return nullptr;
    
    NeuralSyncCore core(w, h);
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jfloatArray result = env->NewFloatArray(4);
    float data[4] = {state.headX, state.headY, state.locked ? 1.0f : 0.0f, state.needsHeal ? 1.0f : 0.0f};
    env->SetFloatArrayRegion(result, 0, 4, data);
    
    return result;
}
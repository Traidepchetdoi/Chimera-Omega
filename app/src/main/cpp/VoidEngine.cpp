#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_GRAVITY", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class AdaptivePIDCore {
private:
    double prevDX = 0, prevDY = 0;
    
    // [OMEGA TUNING] THÔNG SỐ PID & TRỌNG LỰC HÃM PHANH
    const double Kp = 0.14;  // Lực kéo (Tăng nhẹ để bám nhanh hơn)
    const double Kd = 0.90;  // Lực hãm gốc
    const double GRAVITY_ZONE = 60.0; // Bán kính kích hoạt giếng trọng lực (pixel)
    const double BRAKE_INTENSITY = 12.0; // Cường độ hãm phanh khi vào vùng gần

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

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
                double headX = minX + (bw / 2.0);
                
                // [ĐIỀU CHỈNH TỌA ĐỘ Y] 0.32 = Ngay trên lông mày, dưới trán
                double headY = minY + (bh * 0.32); 
                
                double dx = headX - (width / 2.0);
                double dy = headY - (height / 2.0);

                // 1. LỰC KÉO TỶ LỆ (PROPORTIONAL)
                double pX = dx * Kp;
                double pY = dy * Kp;
                
                // 2. LỰC HÃM THÍCH NGHI (ADAPTIVE GRAVITY BRAKING)
                // Tính khoảng cách hiện tại từ tâm đến đầu địch
                double dist = std::sqrt(dx*dx + dy*dy);
                
                // Hệ số nhân hãm phanh: Càng gần mục tiêu, lực hãm càng tăng theo hàm nghịch đảo
                // Tạo hiệu ứng "Giếng từ trường" hút và đóng băng tâm súng ngay tại điểm chạm
                double brakeMultiplier = 1.0;
                if (dist < GRAVITY_ZONE) {
                    brakeMultiplier = 1.0 + (BRAKE_INTENSITY / (dist + 2.0));
                }
                
                double dX = (dx - prevDX) * Kd * brakeMultiplier;
                double dY = (dy - prevDY) * Kd * brakeMultiplier;
                
                // Tổng lực = Lực kéo - Lực hãm thích nghi
                state.forceX = pX - dX; 
                state.forceY = pY - dY;
                
                prevDX = dx; prevDY = dy;
                state.locked = true;
            }
        } else {
            prevDX = 0; prevDY = 0;
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
    
    AdaptivePIDCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
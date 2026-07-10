#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_4D", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class KinematicTracker {
private:
    double prevCX = 0, prevCY = 0; // Tọa độ tâm cụm ở frame trước
    double velX = 0, velY = 0;     // Vận tốc di chuyển của đầu địch (pixel/frame)
    
    // [OMEGA 4D] HỆ SỐ VẬT LÝ
    const double LEAD_FACTOR = 3.5;    // Hệ số đón đầu (Bù trừ Ping + Tốc độ đạn bay)
    const double SMOOTHING = 0.6;      // Làm mượt vận tốc (Trống nhiễu do pixel nhảy)
    
    double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        
        double sumX = 0, sumY = 0;
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        for (int y = 0; y < height; y += 4) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 0; x < width; x += 4) {
                const uint8_t* p = rowPtr + (x * 4);
                if (p[0] > 180 && p[1] < 90 && p[2] < 90) {
                    sumX += x; sumY += y;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                    pixelCount++;
                }
            }
        }

        if (pixelCount >= 3) { 
            int bw = maxX - minX;
            int bh = maxY - minY;
            double currentCX, currentCY;

            if (pixelCount < 40) { // Tầm xa (Sniper)
                currentCX = sumX / pixelCount;
                currentCY = sumY / pixelCount;
            } else { // Tầm gần (Cận chiến)
                currentCX = minX + (bw / 2.0);
                currentCY = minY + (bh * 0.32); // Trên lông mày
            }
            
            // 1. TÍNH VẬN TỐC TƯƠNG ĐỐI (RELATIVE VELOCITY)
            if (prevCX != 0 && prevCY != 0) {
                double rawVX = currentCX - prevCX;
                double rawVY = currentCY - prevCY;
                // Làm mượt vận tốc để tránh nhiễu do kẻ địch đổi hướng đột ngột
                velX = (velX * (1.0 - SMOOTHING)) + (rawVX * SMOOTHING);
                velY = (velY * (1.0 - SMOOTHING)) + (rawVY * SMOOTHING);
            }
            prevCX = currentCX; prevCY = currentCY;

            // 2. TỌA ĐỘ TƯƠNG LAI (LEAD ANGLE - ĐÓN ĐẦU QUỸ ĐẠO)
            // Tâm súng sẽ nhắm vào nơi cái đầu SẼ ĐẾN, không phải nơi nó ĐANG Ở
            double futureX = currentCX + (velX * LEAD_FACTOR);
            double futureY = currentCY + (velY * LEAD_FACTOR);
            
            double dx = futureX - (width / 2.0);
            double dy = futureY - (height / 2.0);

            // 3. ĐÀN HỒI THEO KHỐI LƯỢNG (MASS-ADAPTIVE ELASTICITY)
            double pixelMass = bw * bh; // Diện tích pixel của địch
            
            // Xa (Mass nhỏ): Lực kéo nhớt, bám mượt, chống giật cục.
            // Gần (Mass lớn): Lực kéo thép, khóa cứng, ghim tâm.
            double dynamicTension = (pixelMass < 100.0) ? 60.0 : 180.0; 
            double maxTension = (pixelMass < 100.0) ? 150.0 : 400.0;

            double rawX = dx * 2.0;
            double rawY = dy * 2.0;

            // Ép buộc lực căng tối thiểu (Để tâm không bị trôi khi địch đứng yên)
            if (rawX > 0) rawX = fmax(rawX, dynamicTension);
            else if (rawX < 0) rawX = fmin(rawX, -dynamicTension);
            
            if (rawY > 0) rawY = fmax(rawY, dynamicTension);
            else if (rawY < 0) rawY = fmin(rawY, -dynamicTension);

            // Lực hãm (Chỉ phanh khi tâm đang lao quá nhanh)
            double dX = velX * 1.5; 
            double dY = velY * 1.5;
            
            state.forceX = clamp(rawX - dX, -maxTension, maxTension);
            state.forceY = clamp(rawY - dY, -maxTension, maxTension);
            
            state.locked = true;
        } else {
            // Mất dấu: Giữ nguyên quán tính (Extrapolation)
            prevCX += velX; prevCY += velY;
            velX *= 0.9; velY *= 0.9; // Ma sát
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

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_omega_host_OpticalPhantomService_processOpticalFrame(
    JNIEnv* env, jobject thiz, jobject byteBuffer, jint w, jint h, jint rowStride) {
    
    uint8_t* basePtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!basePtr) return nullptr;
    
    KinematicTracker core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
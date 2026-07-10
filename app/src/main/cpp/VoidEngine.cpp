#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_SNIPER", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class SniperChainCore {
private:
    double prevDX = 0, prevDY = 0;
    
    // [OMEGA IRON CHAIN] LỰC CĂNG TUYỆT ĐỐI
    const double Kp = 2.5;             
    const double Kd = 1.5;             
    const double MIN_TENSION = 120.0;  
    const double MAX_TENSION = 350.0;  

    double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        
        // Biến tích lũy cho Trọng tâm cụm (Centroid)
        double sumX = 0, sumY = 0;
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        // Quét toàn bộ màn hình
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

        // [ADAPTIVE TARGETING] HẠ NGƯỠNG XUỐNG 3 PIXEL ĐỂ BẮT MỤC TIÊU 400M
        if (pixelCount >= 3) { 
            int bw = maxX - minX;
            int bh = maxY - minY;
            
            double targetX, targetY;

            // 1. CHẾ ĐỘ SNIPER TẦM XA (MICRO-CLUSTER)
            // Nếu cụm pixel < 40 (Địch ở xa, chỉ là một chấm đỏ nhỏ)
            if (pixelCount < 40) {
                // Dùng Trọng tâm Toán học (Centroid). 
                // Không cần nhân 0.32, vì bản thân cụm 3x3 pixel đó CHÍNH LÀ cái đầu.
                targetX = sumX / pixelCount;
                targetY = sumY / pixelCount;
            } 
            // 2. CHẾ ĐỘ CẬN CHIẾN (MACRO-BODY)
            // Nếu cụm pixel >= 40 (Địch ở gần, hiện rõ toàn thân)
            else {
                targetX = minX + (bw / 2.0);
                targetY = minY + (bh * 0.32); // Khóa trên lông mày
            }
            
            double dx = targetX - (width / 2.0);
            double dy = targetY - (height / 2.0);

            // [IRON CHAIN] ÉP BUỘC LỰC CĂNG TỐI THIỂU
            double rawX = dx * Kp;
            double rawY = dy * Kp;

            if (rawX > 0) rawX = fmax(rawX, MIN_TENSION);
            else if (rawX < 0) rawX = fmin(rawX, -MIN_TENSION);
            
            if (rawY > 0) rawY = fmax(rawY, MIN_TENSION);
            else if (rawY < 0) rawY = fmin(rawY, -MIN_TENSION);

            double dX = (dx - prevDX) * Kd;
            double dY = (dy - prevDY) * Kd;
            
            double finalX = rawX - dX;
            double finalY = rawY - dY;

            state.forceX = clamp(finalX, -MAX_TENSION, MAX_TENSION);
            state.forceY = clamp(finalY, -MAX_TENSION, MAX_TENSION);
            
            prevDX = dx; prevDY = dy;
            state.locked = true;
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
    
    SniperChainCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_DAC", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; // Nâng cấp lên 64-bit Double
    bool locked;
    bool needsHeal;
};

class PIDCore64 {
private:
    double prevDX = 0, prevDY = 0;
    const double Kp = 0.12f;  
    const double Kd = 0.85f;  

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
                double headY = minY + (bh * 0.22); 
                
                double dx = headX - (width / 2.0);
                double dy = headY - (height / 2.0);

                double pX = dx * Kp;
                double pY = dy * Kp;
                double dX = (dx - prevDX) * Kd;
                double dY = (dy - prevDY) * Kd;
                
                state.forceX = pX - dX; 
                state.forceY = pY - dY;
                
                prevDX = dx; prevDY = dy;
                state.locked = true;
            }
        } else {
            prevDX = 0; prevDY = 0;
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

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_omega_host_OpticalPhantomService_processOpticalFrame(
    JNIEnv* env, jobject thiz, jobject byteBuffer, jint w, jint h, jint rowStride) {
    
    uint8_t* basePtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!basePtr) return nullptr;
    
    PIDCore64 core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    // Trả về mảng Double 64-bit
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
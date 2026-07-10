#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <time.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_QUANTUM", __VA_ARGS__)

struct TargetState {
    double forceX, forceY; 
    bool locked;
    bool needsHeal;
};

class QuantumAttractorCore {
private:
    double prevCX = 0, prevCY = 0; 
    double prevTime = 0;
    double velX = 0, velY = 0; 
    
    // [OMEGA QUANTUM] THÔNG SỐ TRƯỜNG HẤP DẪN
    const double Kp = 2.5;             
    const double Kd = 0.15;            
    const double MIN_TENSION = 120.0;  
    const double MAX_TENSION = 400.0;

    double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    double getHardwareTime() {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return ts.tv_sec + ts.tv_nsec / 1e9;
    }

public:
    TargetState ProcessFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetState state = {0, 0, false, false};
        
        // [TƯ DUY ĐỈNH CAO 1: TÂM TỶ TRỌNG SẮC ĐỘ - INTENSITY-WEIGHTED CENTROID]
        // Không đếm pixel. Đo "trọng lượng" của màu đỏ.
        // Dù cái đầu chỉ là 1.5 pixel mờ ảo, đỉnh của sóng màu đỏ vẫn nằm chính xác giữa hốc mắt.
        double sumWX = 0, sumWY = 0, totalWeight = 0;
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int pixelCount = 0;

        for (int y = 0; y < height; y += 4) {
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = 0; x < width; x += 4) {
                const uint8_t* p = rowPtr + (x * 4);
                
                // Tính độ thuần khiết của màu đỏ (Red Dominance)
                double redDom = p[0] - (p[1] + p[2]) * 0.5;
                
                if (redDom > 40 && p[0] > 150) {
                    double weight = redDom; // Pixel càng đỏ đậm, trọng lực càng lớn
                    sumWX += x * weight;
                    sumWY += y * weight;
                    totalWeight += weight;
                    
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                    pixelCount++;
                }
            }
        }

        if (totalWeight > 0 && pixelCount >= 2) { 
            int bw = maxX - minX;
            int bh = maxY - minY;
            
            // Tọa độ thực sự của cái đầu (Chính xác đến 0.0001px nhờ nội suy trọng số)
            double currentCX = sumWX / totalWeight;
            double currentCY = sumWY / totalWeight;

            // [TƯ DUY ĐỈNH CAO 2: HIỆU CHỈNH THIÊN VỊ PHỐI CẢNH (PERSPECTIVE BIAS)]
            // Khi địch ở cực xa, GPU khử răng cưa làm tâm cụm màu đỏ bị lệch xuống.
            // Ta bù trừ một lượng Y nghịch biến với kích thước pixel để nhấc tâm lên đúng đỉnh đầu.
            double pixelMass = bw * bh;
            double perspectiveBiasY = 0.0;
            if (pixelMass < 20.0) { // Địch ở cực xa (dưới 5x5 pixel)
                perspectiveBiasY = -1.5; 
            } else if (pixelMass < 100.0) {
                perspectiveBiasY = -0.5;
            }
            currentCY += perspectiveBiasY;
            
            // 1. ĐỒNG BỘ HÓA PHẦN CỨNG (HARDWARE TIME SYNC)
            double currentTime = getHardwareTime();
            double dt = currentTime - prevTime;
            if (dt < 0.001) dt = 0.001;

            // 2. TÍNH VẬN TỐC THỰC CỦA ĐÁM MÂY XÁC SUẤT
            if (prevTime > 0) {
                double rawVX = (currentCX - prevCX) / dt;
                double rawVY = (currentCY - prevCY) / dt;
                velX = (velX * 0.7) + (rawVX * 0.3);
                velY = (velY * 0.7) + (rawVY * 0.3);
            }
            prevCX = currentCX; prevCY = currentCY; prevTime = currentTime;

            double dx = currentCX - (width / 2.0);
            double dy = currentCY - (height / 2.0);

            // [TƯ DUY ĐỈNH CAO 3: TRƯỜNG HẤP DẪN GAUSS (GAUSSIAN ATTRACTOR FIELD)]
            double dynamicKp = Kp;
            
            // Nếu đích đến quá nhỏ (cỡ lượng tử), làm mềm lực kéo để tránh dao động (Quantum Jitter)
            if (pixelMass < 15.0) {
                dynamicKp *= 0.6; 
            }

            double rawX = dx * dynamicKp;
            double rawY = dy * dynamicKp;

            if (rawX > 0) rawX = fmax(rawX, MIN_TENSION);
            else if (rawX < 0) rawX = fmin(rawX, -MIN_TENSION);
            
            if (rawY > 0) rawY = fmax(rawY, MIN_TENSION);
            else if (rawY < 0) rawY = fmin(rawY, -MIN_TENSION);

            double dampX = velX * Kd;
            double dampY = velY * Kd;
            
            state.forceX = clamp(rawX - dampX, -MAX_TENSION, MAX_TENSION);
            state.forceY = clamp(rawY - dampY, -MAX_TENSION, MAX_TENSION);
            
            state.locked = true;
        } else {
            prevTime = 0;
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
    
    QuantumAttractorCore core;
    TargetState state = core.ProcessFrame(basePtr, w, h, rowStride);
    
    jdoubleArray result = env->NewDoubleArray(4);
    double data[4] = {state.forceX, state.forceY, state.locked ? 1.0 : 0.0, state.needsHeal ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(result, 0, 4, data);
    
    return result;
}
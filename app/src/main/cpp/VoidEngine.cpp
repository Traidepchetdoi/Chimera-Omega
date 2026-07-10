#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_VISION", __VA_ARGS__)

struct TargetLock {
    float headX;
    float headY;
    bool locked;
};

class CranialGeometryCore {
private:
    int screenW, screenH;
    int scanStartX, scanEndX, scanStartY, scanEndY;

public:
    CranialGeometryCore(int w, int h) : screenW(w), screenH(h) {
        scanStartX = w / 4;
        scanEndX = (w * 3) / 4;
        scanStartY = h / 4;
        scanEndY = (h * 3) / 4;
    }

    // Quét trực tiếp trên bộ nhớ ByteBuffer (Không cần AHardwareBuffer)
    TargetLock ScanFrame(const uint8_t* basePtr, int width, int height, int rowStride) {
        TargetLock lock = {0, 0, false};
        
        int minX = width, maxX = 0;
        int minY = height, maxY = 0;
        int pixelCount = 0;

        for (int y = scanStartY; y < scanEndY; y += 3) {
            // Nhảy đến đầu dòng Y dựa trên rowStride (Bao gồm cả padding của GPU)
            const uint8_t* rowPtr = basePtr + (y * rowStride);
            for (int x = scanStartX; x < scanEndX; x += 3) {
                // Mỗi pixel RGBA_8888 chiếm 4 bytes
                const uint8_t* pixelPtr = rowPtr + (x * 4); 
                uint8_t r = pixelPtr[0]; 
                uint8_t g = pixelPtr[1];
                uint8_t b = pixelPtr[2];

                // Phát hiện Outline Đỏ
                if (r > 180 && g < 90 && b < 90) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    pixelCount++;
                }
            }
        }

        if (pixelCount > 40 && maxX > minX && maxY > minY) {
            int boxWidth = maxX - minX;
            int boxHeight = maxY - minY;

            if (boxHeight > boxWidth * 0.6f) {
                float headX = minX + (boxWidth / 2.0f);
                float headY = minY + (boxHeight * 0.20f);
                
                lock.headX = headX;
                lock.headY = headY;
                lock.locked = true;
            }
        }
        return lock;
    }
};

// =====================================================================
// CỔNG JNI: NHẬN DIRECT BYTE BUFFER (TƯƠNG THÍCH NGƯỢC XUỐNG API 21)
// =====================================================================
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_omega_host_OpticalPhantomService_processOpticalFrame(
    JNIEnv* env, jobject thiz, jobject byteBuffer, jint w, jint h, jint rowStride) {
    
    // Chiếm đoạt địa chỉ vật lý của ByteBuffer từ Java
    uint8_t* basePtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!basePtr) return nullptr;
    
    CranialGeometryCore core(w, h);
    TargetLock lock = core.ScanFrame(basePtr, w, h, rowStride);
    
    jfloatArray result = env->NewFloatArray(3);
    float data[3] = {lock.headX, lock.headY, lock.locked ? 1.0f : 0.0f};
    env->SetFloatArrayRegion(result, 0, 3, data);
    
    return result;
}
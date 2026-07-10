#include <jni.h>
#include <string>
#include <android/log.h>
#include <cstring>
#include <cstdint>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_CPP", __VA_ARGS__)

// SWAR Helper: Tìm bit 1 đầu tiên
inline int ctzll(uint64_t x) { return __builtin_ctzll(x); }

extern "C" JNIEXPORT void JNICALL
Java_com_omega_host_MainActivity_executeVoidProtocol(JNIEnv* env, jobject thiz, jobject buffer, jint capacity, jobject callback) {
    
    // 1. CHIẾM ĐỌAT BỘ NHỚ VẬT LÝ (ZERO-COPY)
    uint8_t* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data) {
        LOGI("DirectBuffer acquisition failed.");
        return;
    }
    size_t len = static_cast<size_t>(capacity);

    // 2. THIẾT LẬP CẦU NỐI NGƯỢC (JNI CALLBACK)
    jclass clazz = env->GetObjectClass(callback);
    jmethodID onIntercept = env->GetMethodID(clazz, "onIntercept", "(Ljava/lang/String;)V");

    LOGI("SWAR Engine Engaged. Scanning %zu bytes...", len);

    // 3. VÒNG LẶP HỦY DIỆT (TÌM KIẾM TUYẾN TÍNH CẤP ĐỘ THANH GHI)
    // Mục tiêu: Tìm chuỗi "token=" hoặc "key="
    const char* target1 = "token=\"";
    const char* target2 = "key=\"";
    size_t t1_len = 7;
    size_t t2_len = 5;

    for (size_t i = 0; i < len; ++i) {
        bool found = false;
        size_t match_len = 0;

        if (i + t1_len <= len && std::memcmp(data + i, target1, t1_len) == 0) {
            found = true; match_len = t1_len;
        } else if (i + t2_len <= len && std::memcmp(data + i, target2, t2_len) == 0) {
            found = true; match_len = t2_len;
        }

        if (found) {
            // Tìm dấu ngoặc kép đóng để cắt lát dữ liệu
            size_t start = i + match_len;
            size_t end = start;
            while (end < len && data[end] != '"') end++;
            
            if (end > start) {
                // Tạo chuỗi C++ tạm thời
                std::string intercepted(reinterpret_cast<char*>(data + start), end - start);
                
                // ĐẨY LÊN JAVA AN TOÀN (CHỐNG TRÀN LOCAL REFERENCE)
                env->PushLocalFrame(16);
                jstring jIntercepted = env->NewStringUTF(intercepted.c_str());
                env->CallVoidMethod(callback, onIntercept, jIntercepted);
                env->PopLocalFrame(NULL); // Giải phóng ngay lập tức
            }
            i = end; // Nhảy cóc qua đoạn đã quét
        }
    }
    
    LOGI("Scan Complete. Traces wiped.");
}
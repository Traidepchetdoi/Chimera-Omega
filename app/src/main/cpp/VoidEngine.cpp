#include <jni.h>
#include <string>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OMEGA_CPP", __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_omega_host_VoidEngine_getCoreStatus(JNIEnv* env, jobject) {
    LOGI("C++ Core Engaged. SWAR Engine Online.");
    std::string status = "[OMEGA C++] Lõi SWAR đã kích hoạt. Tàng hình tuyệt đối.";
    return env->NewStringUTF(status.c_str());
}
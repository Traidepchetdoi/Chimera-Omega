# 🛡️ OMEGA PROGUARD RULES: Bảo vệ code khỏi reverse engineering

# Giữ lại các class WebSocket (không obfuscate)
-keep class org.java_websocket.** { *; }
-keep class com.chimera.touch.** { *; }

# Giữ lại ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Giữ lại annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Không warn về các thư viện bên thứ 3
-dontwarn org.java_websocket.**
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Tối ưu hóa
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Giữ lại BuildConfig
-keep class com.chimera.touch.BuildConfig { *; }

# Giữ lại Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Giữ lại enum
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 🛡️ OMEGA PROGUARD RULES
-keep class org.java_websocket.** { *; }
-keep class com.chimera.touch.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn org.java_websocket.**
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-keep class com.chimera.touch.BuildConfig { *; }

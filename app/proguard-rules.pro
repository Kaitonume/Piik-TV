# libwebrtc usa JNI: le classi org.webrtc e org.jni_zero (registrazione JNI) devono restare intatte
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-keepclasseswithmembers class * { native <methods>; }
-dontwarn org.webrtc.**
-dontwarn org.jni_zero.**
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Classi dell'app chiamate da libwebrtc via JNI/interfacce
-keep class it.mbs.piiktv.** { *; }

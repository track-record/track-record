# R8 rules for release builds (REVIEW.md C-08; DECISIONS.md D-273). The libraries bring their own:
# kotlinx.serialization, Room, Media3, OkHttp, Compose and AndroidX WebKit.

# Chromaprint's native methods are found by class and method name (fingerprint_jni.cpp).
-keep class com.trackrecord.fingerprint.Chromaprint { native <methods>; }

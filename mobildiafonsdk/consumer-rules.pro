# MobilDiafon SDK — tüketici ProGuard kuralları (host uygulamaya otomatik uygulanır)

# WebRTC JNI köprüsü: isimler korunmalı
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Socket.io / engine.io + okhttp
-keep class io.socket.** { *; }
-dontwarn io.socket.**
-dontwarn okhttp3.**
-dontwarn okio.**

# SDK genel API'si
-keep class com.mobildiafon.rtc.** { *; }

package com.mobildiafon.rtc;

/**
 * Cihaza özel kararlı kimlik sağlayıcı.
 * Multitek monitörde host genelde MAC verir:
 *
 *   MobilDiafon.setDeviceIdProvider(() ->
 *       MyUtils.getInstance().getMACAddress().replace(":", "").toLowerCase());
 *
 * null/boş dönerse SDK ANDROID_ID'ye düşer. Bu kimlik backend'de
 * "device-<id>" kullanıcısına karşılık gelir; DEĞİŞİRSE monitör yeni cihaz sayılır.
 */
public interface DeviceIdProvider {
    String getDeviceId();
}

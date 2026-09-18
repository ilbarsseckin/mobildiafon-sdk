package com.mobildiafon.rtc;

/**
 * DONANIM + UI köprüsü — host (Multitek smarthome uygulaması) uygular.
 * SDK donanıma (I2C) ve ekrana (R/layout) DOKUNMAZ; bu arayüzle host'a devreder.
 *
 * Kayıt:  MobilDiafon.setHost(new MultitekDiafonHost(appContext));
 *
 * Zorunlu tek metod showIncoming(); geri kalanı senaryolarına göre uygula.
 * Tüm çağrılar ana (UI) thread'de gelir.
 */
public interface DiafonHost {

    // ---------------- UI (host kendi Activity'sini açar) ----------------

    /** QR ziyaretçi / panel çağrısı geldi. Host gelen-çağrı ekranını açsın. */
    void showIncoming(String callId, String callerName);

    /** door:start — monitör kapı kamerasıyla daireyi arasın (B panel akışı).
     *  Host, MobilDiafonCall'ı kuran door ekranını açsın. Kullanmıyorsan boş bırak. */
    default void showDoorCall(String apartmentId, String buildingQrToken) {}

    // ---------------- Donanım (Multitek I2C) — host uygular ----------------

    /** Ev sahibi telefondan "Kapıyı Aç" dedi. Host röleyi sürsün: I2CUtil.openDoor(). */
    default void openDoor() {}

    /** Çağrıyı daireden (telefon) biri cevapladı. Host ses/flag ayarlasın:
     *  setVoiceCloudSpeak / monitorAnswered / mobilAnswered. */
    default void onPhoneAnswered(String callId) {}

    /** C: telefon canlı kapı görüntüsü istedi. Host analog hattı açsın (camera 0 sinyali):
     *  callToAnalogDoor(block, 0, 1). SES AÇMA (sessiz izleme). */
    default void onDoorViewStart() {}

    /** C: canlı görüntü bitti. Host analog hattı kapatsın: closeAnalogConnection(). */
    default void onDoorViewStop() {}

    /** Bir çağrı sonlandı (reason: ended/taken/rejected/local...). Opsiyonel temizlik. */
    default void onCallEnded(String reason) {}
}

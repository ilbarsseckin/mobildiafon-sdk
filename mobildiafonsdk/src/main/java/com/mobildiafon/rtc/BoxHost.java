package com.mobildiafon.rtc;

/**
 * DiafonBox donanım köprüsü — ekransız kapı kutusu host'u uygular.
 * SDK röleye/GPIO'ya dokunmaz; bu arayüzle host'a devreder.
 *
 * Kayıt:  MobilDiafonBox.setHost(new MyBoxHost());
 */
public interface BoxHost {
    /** Sakin telefondan "Kapıyı Aç" dedi. Host kapı rölesini sürsün (GPIO/röle). */
    default void openDoor() {}
    /** Bir çağrı sonlandı (reason: ended/rejected/taken/unavailable/local...). */
    default void onCallEnded(String reason) {}
}

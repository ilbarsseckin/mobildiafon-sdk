package com.mobildiafon.rtc;

import android.content.Context;

/**
 * MobilDiafon SDK giriş noktası (facade).
 *
 * Kurulum (Application.onCreate):
 *   MobilDiafon.setDeviceIdProvider(() -> MyUtils.getInstance().getMACAddress().replace(":","").toLowerCase());
 *   MobilDiafon.setHost(new MultitekDiafonHost(getApplicationContext()));
 *   MobilDiafon.start(this);   // aktive edilmişse alıcı servisini ayağa kaldırır
 *
 * Aktivasyon (sakin OTP ile giriş yaptıktan sonra, kişi token'ıyla):
 *   MobilDiafon.activate(ctx, personToken, apartmentId, "Monitor 20", (ok, msg) -> { ... });
 */
public final class MobilDiafon {

    private static DiafonHost host;
    private static DeviceIdProvider deviceIdProvider;

    private MobilDiafon() {}

    // ---- yapılandırma ----
    public static MobilDiafonConfig config() { return MobilDiafonConfig.get(); }

    public static void setHost(DiafonHost h) { host = h; }
    public static DiafonHost host() { return host; }

    public static void setDeviceIdProvider(DeviceIdProvider p) { deviceIdProvider = p; }
    public static DeviceIdProvider deviceIdProvider() { return deviceIdProvider; }

    // ---- aktivasyon / durum ----

    /** Monitörü kalıcı cihaz olarak aktive et. personToken = sakinin OTP ile aldığı kişi JWT'si. */
    public static void activate(Context ctx, String personToken, String apartmentId,
                                String name, MobilDiafonManager.ActivateCallback cb) {
        MobilDiafonManager.get(ctx).activate(personToken, apartmentId, name, cb);
    }

    public static boolean isActive(Context ctx) { return MobilDiafonManager.get(ctx).isActive(); }

    public static String apartmentId(Context ctx) { return MobilDiafonManager.get(ctx).getApartmentId(); }

    /** Aktivasyonu sıfırla (çıkış). */
    public static void logout(Context ctx) { MobilDiafonManager.get(ctx).clear(); }

    // ---- alıcı servisi ----

    /** Aktive edilmişse kalıcı socket servisini başlatır. Application.onCreate'te çağır. */
    public static void start(Context ctx) { MobilDiafonService.startIfActive(ctx); }
}

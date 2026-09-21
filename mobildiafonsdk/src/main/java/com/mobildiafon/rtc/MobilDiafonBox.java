package com.mobildiafon.rtc;

import android.content.Context;

import org.webrtc.SurfaceViewRenderer;

import java.util.List;

/**
 * MobilDiafon — DIAFONBOX (ekransız kapı kutusu) giriş noktası (facade).
 *
 * Monitörden farkı: daireye değil BİNAYA bağlıdır, MAC ile aktive edilir,
 * gelen çağrı almaz; ziyaretçi daire seçince o daireyi ARAR (video) + kapı açar.
 * Arama motoru monitörle aynıdır (MobilDiafonCall, buildingQrToken'lı akış).
 *
 * Kurulum (Application.onCreate):
 *   MobilDiafon.setDeviceIdProvider(() -> myMac());   // MAC = kutu kimliği
 *   MobilDiafonBox.setHost(new MyBoxHost());          // openDoor() rölesi
 *   MobilDiafonBox.activate(ctx, (ok, msg) -> { ... });  // MAC -> bina
 *
 * Ziyaretçi daire seçince:
 *   MobilDiafonCall call = MobilDiafonBox.call(ctx, apartmentId, localView, remoteView, listener);
 *   ... call.setMicMuted(...), call.hangup();
 */
public final class MobilDiafonBox {

    private static BoxHost host;

    private MobilDiafonBox() {}

    public static void setHost(BoxHost h) { host = h; }
    public static BoxHost host() { return host; }

    /** Kutuyu binaya aktive et (MAC panele eklenmiş olmalı). Boot'ta çağır. */
    public static void activate(Context ctx, MobilDiafonManager.ActivateCallback cb) {
        MobilDiafonManager.get(ctx).activateBox(cb);
    }

    public static boolean isActive(Context ctx) { return MobilDiafonManager.get(ctx).isBoxActive(); }

    /** Bu kutunun binasındaki daireler (box-activate'ten önbelleğe alınır). */
    public static List<Apartment> apartments(Context ctx) { return MobilDiafonManager.get(ctx).getApartments(); }

    public static String buildingId(Context ctx) { return MobilDiafonManager.get(ctx).getBuildingId(); }

    /** Aktivasyonu sıfırla. */
    public static void logout(Context ctx) { MobilDiafonManager.get(ctx).clearBox(); }

    /** Seçilen daireyi ara. Kutu, binanın QR token'ıyla guest gibi bağlanır → daire sakinleri çalar. */
    public static MobilDiafonCall call(Context ctx, String apartmentId,
                                       SurfaceViewRenderer localView, SurfaceViewRenderer remoteView,
                                       MobilDiafonCall.Listener listener) {
        String qr = MobilDiafonManager.get(ctx).getBuildingQrToken();
        MobilDiafonCall call = new MobilDiafonCall(ctx, apartmentId, qr, localView, remoteView, listener);
        call.start();
        return call;
    }
}

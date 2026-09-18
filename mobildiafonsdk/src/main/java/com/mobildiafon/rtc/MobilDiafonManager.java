package com.mobildiafon.rtc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cihaz token'ı saklama + backend HTTP (aktivasyon / guest-token).
 *
 * AKTİVASYON MODELİ:
 *   Sakin OTP ile giriş yapar (kişi token'ı). Bu token'la POST /calls/monitor-activate
 *   çağrılır; backend "device-<deviceId>" kullanıcısını + onaylı sakini kurar ve
 *   3650 günlük CİHAZ token'ı döner. Bu token K_TOKEN'a yazılır; monitör bundan sonra
 *   sakinin telefonundan AYRI bir kullanıcı olarak bağlanır (presence çakışmaz).
 */
public final class MobilDiafonManager {

    private static final String TAG = "MobilDiafonMgr";

    private static final String PREFS       = "mobildiafon";
    private static final String K_TOKEN     = "md_token";          // CİHAZ token'ı
    private static final String K_APARTMENT = "md_apartment_id";
    private static final String K_BLD_QR    = "md_building_qr";
    private static final String K_ACTIVE    = "md_active";

    public interface ActivateCallback {
        /** Ana thread'de çağrılır. */
        void onResult(boolean success, String message);
    }

    private static MobilDiafonManager sInstance;
    public static synchronized MobilDiafonManager get(Context ctx) {
        if (sInstance == null) sInstance = new MobilDiafonManager(ctx.getApplicationContext());
        return sInstance;
    }

    private final Context appCtx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private MobilDiafonManager(Context ctx) {
        this.appCtx = ctx;
        this.prefs  = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- durum ----------------

    public String getToken()          { return prefs.getString(K_TOKEN, ""); }
    public String getApartmentId()    { return prefs.getString(K_APARTMENT, ""); }
    public String getBuildingQrToken(){ return prefs.getString(K_BLD_QR, ""); }
    public boolean isActive()         { return prefs.getBoolean(K_ACTIVE, false) && !getToken().isEmpty(); }

    public void setBuildingQrToken(String qr) { prefs.edit().putString(K_BLD_QR, qr == null ? "" : qr).apply(); }

    public void clear() {
        prefs.edit().remove(K_TOKEN).remove(K_APARTMENT).remove(K_BLD_QR).putBoolean(K_ACTIVE, false).apply();
    }

    /** Cihaz kimliği (MAC vb.). Provider yoksa ANDROID_ID'ye düşer. */
    public String getDeviceId() {
        DeviceIdProvider p = MobilDiafon.deviceIdProvider();
        if (p != null) {
            try {
                String id = p.getDeviceId();
                if (id != null && !id.trim().isEmpty()) return id.trim();
            } catch (Throwable t) { Log.e(TAG, "deviceIdProvider patladi", t); }
        }
        String aid = Settings.Secure.getString(appCtx.getContentResolver(), Settings.Secure.ANDROID_ID);
        return aid != null ? aid.toLowerCase() : "unknown";
    }

    // ---------------- aktivasyon ----------------

    /**
     * POST /calls/monitor-activate  (Authorization: Bearer personToken)
     * body { apartmentId, deviceId, name? } -> { success, token, deviceUserId }
     * Dönen 'token' CİHAZ token'ı olarak saklanır.
     */
    public void activate(final String personToken, final String apartmentId,
                         final String name, final ActivateCallback cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("apartmentId", apartmentId)
                        .put("deviceId", getDeviceId());
                if (name != null && !name.isEmpty()) body.put("name", name);

                JSONObject res = postJson(MobilDiafonConfig.get().apiBase + "/calls/monitor-activate",
                        body, personToken);

                String token = res.optString("token", "");
                if (res.optBoolean("success", false) && !token.isEmpty()) {
                    prefs.edit()
                            .putString(K_TOKEN, token)
                            .putString(K_APARTMENT, apartmentId)
                            .putBoolean(K_ACTIVE, true)
                            .apply();
                    postMain(cb, true, "Aktive edildi");
                } else {
                    postMain(cb, false, res.optString("message", "Aktivasyon başarısız"));
                }
            } catch (Exception e) {
                Log.e(TAG, "activate", e);
                postMain(cb, false, "Aktivasyon hatası: " + e.getMessage());
            }
        });
    }

    /** POST /auth/guest-token { qrToken } -> { token } (senkron, arka thread'den çağır). */
    public String getGuestTokenSync() throws Exception {
        String qr = getBuildingQrToken();
        if (qr.isEmpty()) throw new Exception("building QR token yok");
        JSONObject res = postJson(MobilDiafonConfig.get().apiBase + "/auth/guest-token",
                new JSONObject().put("qrToken", qr), null);
        String t = res.optString("token", "");
        if (t.isEmpty()) throw new Exception(res.optString("message", "guest-token alınamadı"));
        return t;
    }

    // ---------------- HTTP yardımcı ----------------

    private static JSONObject postJson(String urlStr, JSONObject body, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (bearer != null && !bearer.isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(out); }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.length() == 0 ? new JSONObject() : new JSONObject(sb.toString());
        } finally { conn.disconnect(); }
    }

    private void postMain(final ActivateCallback cb, final boolean ok, final String msg) {
        if (cb == null) return;
        main.post(() -> cb.onResult(ok, msg));
    }
}

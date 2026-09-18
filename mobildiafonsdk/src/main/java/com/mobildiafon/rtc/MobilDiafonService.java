package com.mobildiafon.rtc;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

/**
 * Alıcı servisi (DÜZ servis — foreground DEĞİL).
 * Monitör açıkken cihaz token'ıyla socket.io bağlantısını tutar; olay gelince
 * DiafonHost'a devreder (donanım/ekran host tarafında).
 *
 * NOT: foreground service bu cihazda Linphone başlatılırken çakışıp süreci
 * düşürüyordu. Monitör uygulaması hep açık olduğundan düz servis yeterli.
 * Socket, Linphone otursun diye config.startDelayMs kadar gecikmeyle açılır.
 */
public class MobilDiafonService extends Service implements MobilDiafonReceiver.Listener {

    public static void startIfActive(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!MobilDiafonManager.get(app).isActive()) return;
        app.startService(new Intent(app, MobilDiafonService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            MobilDiafonReceiver r = MobilDiafonReceiver.get();
            r.setListener(this);
            String token = MobilDiafonManager.get(getApplicationContext()).getToken();
            if (token != null && !token.isEmpty()) r.start(getApplicationContext(), token);
        }, MobilDiafonConfig.get().startDelayMs);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private DiafonHost host() { return MobilDiafon.host(); }

    // ---- Receiver.Listener -> DiafonHost köprüsü ----
    @Override public void onIncoming(String callId, String callerName) {
        DiafonHost h = host(); if (h != null) h.showIncoming(callId, callerName);
    }
    @Override public void onConnected() { }
    @Override public void onEnded(String reason) {
        DiafonHost h = host(); if (h != null) h.onCallEnded(reason);
    }
    @Override public void onDoorStart(String apartmentId, String buildingQrToken) {
        DiafonHost h = host(); if (h != null) h.showDoorCall(apartmentId, buildingQrToken);
    }
    @Override public void onDoorOpen(String apartmentId) {
        DiafonHost h = host(); if (h != null) h.openDoor();
    }
    @Override public void onDoorViewStart() {
        DiafonHost h = host(); if (h != null) h.onDoorViewStart();
    }
    @Override public void onDoorViewStop() {
        DiafonHost h = host(); if (h != null) h.onDoorViewStop();
    }
    @Override public void onPhoneAnswered(String callId) {
        DiafonHost h = host(); if (h != null) h.onPhoneAnswered(callId);
    }
}

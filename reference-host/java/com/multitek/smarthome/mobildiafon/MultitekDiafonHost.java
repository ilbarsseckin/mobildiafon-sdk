package com.multitek.smarthome.mobildiafon;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.mobildiafon.rtc.DiafonHost;

/**
 * DiafonHost'un Multitek (com.multitek.smarthome) uygulamasındaki karşılığı.
 * SDK'nın donanım/ekran isteklerini burada I2C ve host Activity'lerine bağlarız.
 * TÜM com.multitek.* referansları BURADA (SDK .aar'ında yok).
 *
 * Application.onCreate:
 *   MobilDiafon.setDeviceIdProvider(() ->
 *       com.multitek.smarthome.utils.MyUtils.getInstance().getMACAddress().replace(":","").toLowerCase());
 *   MobilDiafon.setHost(new MultitekDiafonHost(getApplicationContext()));
 *   MobilDiafon.start(this);
 */
public class MultitekDiafonHost implements DiafonHost {

    private static final String TAG = "MultitekDiafonHost";
    private final Context app;

    public MultitekDiafonHost(Context appContext) {
        this.app = appContext.getApplicationContext();
    }

    // ---------------- UI ----------------

    @Override
    public void showIncoming(String callId, String callerName) {
        Intent i = new Intent(app, MobilDiafonIncomingActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("callId", callId);
        i.putExtra("callerName", callerName);
        app.startActivity(i);
    }

    @Override
    public void showDoorCall(String apartmentId, String buildingQrToken) {
        Intent i = new Intent(app, MobilDiafonDoorActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("apartmentId", apartmentId);
        i.putExtra("buildingQrToken", buildingQrToken);
        app.startActivity(i);
    }

    // ---------------- Donanım (I2C) ----------------

    @Override
    public void openDoor() {
        Log.e(TAG, "openDoor -> I2CUtil.openDoor()");
        new Handler(Looper.getMainLooper()).post(() -> {
            try { android.widget.Toast.makeText(app, "Kapı açılıyor", android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
        });
        try { com.multitek.i2c.I2CUtil.openDoor(); } catch (Throwable t) { Log.e(TAG, "I2CUtil.openDoor", t); }
        try { com.multitek.i2c.I2CUtil2.openDoor(); } catch (Throwable ignored) {}
    }

    @Override
    public void onPhoneAnswered(String callId) {
        Log.e(TAG, "onPhoneAnswered call=" + callId);
        try {
            com.multitek.i2c.I2CUtil.setVoiceCloudSpeak();
            com.multitek.i2c.I2CUtil.monitorAnswered();
            com.multitek.i2c.I2CUtil.mobilAnswered();
        } catch (Throwable t) { Log.e(TAG, "I2CUtil answered", t); }
        try {
            com.multitek.i2c.I2CUtil2.setVoiceCloudSpeak();
            com.multitek.i2c.I2CUtil2.monitorAnswered();
            com.multitek.i2c.I2CUtil2.mobilAnswered();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDoorViewStart() {
        // Sessiz izleme: analog kapı video hattını aç (camera 0 sinyali). SES AÇMA.
        int block = 1;
        try { block = com.multitek.smarthome.utils.MyUtils.getInstance().getDeviceProperties().getBlock(); } catch (Throwable ignored) {}
        try {
            com.multitek.i2c.I2CUtil.callToAnalogDoor(block, 0, 1);
            com.multitek.i2c.I2CUtil2.callToAnalogDoor(block, 0, 1);
        } catch (Throwable t) { Log.e(TAG, "doorView callToAnalogDoor", t); }
    }

    @Override
    public void onDoorViewStop() {
        try {
            com.multitek.i2c.I2CUtil.closeAnalogConnection();
            com.multitek.i2c.I2CUtil2.closeAnalogConnection();
            com.multitek.i2c.I2CUtil.setVoiceDefault("md-view");
            com.multitek.i2c.I2CUtil2.setVoiceDefault("md-view");
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCallEnded(String reason) {
        // Opsiyonel: gerekirse burada ek temizlik.
    }
}

package com.multitek.smarthome.mobildiafon;

import com.mobildiafon.rtc.MobilDiafonCall;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import org.webrtc.SurfaceViewRenderer;

/**
 * QR ile kapı çağrısı geldiğinde monitörde açılan ekran (CALLER tarafı).
 * Kapı kamerasını (camera 0) monitörde gösterir ve daireyi arar → telefona
 * kapı kamerası gider. Analog kapı akışının (MobilDiafonCall) QR karşılığı.
 *
 * Layout: activity_mobildiafon_incoming.xml'i tekrar kullanıyoruz (mdRemote/mdLocal).
 * R importunu kendi paketinle değiştir (com.multitek.smarthome.R).
 */
public class MobilDiafonDoorActivity extends Activity {

    private MobilDiafonCall call;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.util.Log.e("MobilDiafonDoor", "onCreate ACILDI");
        try { android.widget.Toast.makeText(this, "MobilDiafon kapı çağrısı", android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(com.multitek.smarthome.R.layout.activity_mobildiafon_incoming);

        SurfaceViewRenderer local  = findViewById(com.multitek.smarthome.R.id.mdRemote); // BÜYÜK = kapı kamerası
        SurfaceViewRenderer remote = findViewById(com.multitek.smarthome.R.id.mdLocal);  // KÜÇÜK = telefon (cevaplayınca)

        // Bu ekran arayan (kapı istasyonu); "Cevapla/Reddet" gerekmez
        android.view.View a = findViewById(com.multitek.smarthome.R.id.mdAnswer);
        android.view.View rj = findViewById(com.multitek.smarthome.R.id.mdReject);
        if (a != null) a.setVisibility(android.view.View.GONE);
        if (rj != null) rj.setVisibility(android.view.View.GONE);

        remote.setZOrderMediaOverlay(true);  // küçük görüntü büyük görüntünün ÜSTÜnde render olsun

        String apartmentId    = getIntent().getStringExtra("apartmentId");
        String buildingQrToken= getIntent().getStringExtra("buildingQrToken");

        // ANALOG KAPI HATTINI AÇ: GM7150 kapı videosunu camera 0'a getirir
        // (CallOutgoingDoor'daki gibi). Bu olmadan camera 0 siyah gelir.
        int block = 1;
        try {
            block = com.multitek.smarthome.utils.MyUtils.getInstance().getDeviceProperties().getBlock();
            android.util.Log.e("MobilDiafonDoor", "block=" + block + " -> callToAnalogDoor(block,0,1)");
        } catch (Throwable t) { android.util.Log.e("MobilDiafonDoor", "getBlock patladi", t); }
        try { com.multitek.i2c.I2CUtil.callToAnalogDoor(block, 0, 1); android.util.Log.e("MobilDiafonDoor", "I2CUtil.callToAnalogDoor OK"); }
        catch (Throwable t) { android.util.Log.e("MobilDiafonDoor", "I2CUtil.callToAnalogDoor patladi", t); }
        try { com.multitek.i2c.I2CUtil2.callToAnalogDoor(block, 0, 1); android.util.Log.e("MobilDiafonDoor", "I2CUtil2.callToAnalogDoor OK"); }
        catch (Throwable t) { android.util.Log.e("MobilDiafonDoor", "I2CUtil2.callToAnalogDoor patladi", t); }
        try { com.multitek.i2c.I2CUtil.setVoiceMonitorSpeak(); com.multitek.i2c.I2CUtil2.setVoiceMonitorSpeak();
              com.multitek.i2c.I2CUtil.analogMicrophoneEnable(true); com.multitek.i2c.I2CUtil2.analogMicrophoneEnable(true); }
        catch (Throwable t) { android.util.Log.e("MobilDiafonDoor", "voice/mic patladi", t); }

        // sinyal geldi mi diye logla (GM7150: 0=yok,1=zayıf,2=iyi)
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            try { android.util.Log.d("MobilDiafonDoor", "signalState=" + com.multitek.i2c.I2CTransfer.getSignalState()); }
            catch (Throwable ignored) {}
        }, 1500);

        call = new MobilDiafonCall(this, apartmentId, buildingQrToken, local, remote,
                new MobilDiafonCall.Listener() {
                    @Override public void onState(String state) {
                        if (state.equals("accepted")) {
                            // Telefon cevapladı -> panele "cevaplandı" de (dıtt dıtt kessin, ses otursun)
                            android.util.Log.e("MobilDiafonDoor", "accepted -> monitorAnswered");
                            try { com.multitek.i2c.I2CUtil.setVoiceCloudSpeak();
                                  com.multitek.i2c.I2CUtil.monitorAnswered();
                                  com.multitek.i2c.I2CUtil.mobilAnswered(); }
                            catch (Throwable t) { android.util.Log.e("MobilDiafonDoor", "I2CUtil answered patladi", t); }
                            try { com.multitek.i2c.I2CUtil2.setVoiceCloudSpeak();
                                  com.multitek.i2c.I2CUtil2.monitorAnswered();
                                  com.multitek.i2c.I2CUtil2.mobilAnswered(); }
                            catch (Throwable ignored) {}
                        }
                        if (state.startsWith("ended") || state.startsWith("error")) {
                            runOnUiThread(() -> finish());
                        }
                    }
                    @Override public void onRemoteOpenDoor() {
                        // İstenirse burada I2CUtil.openDoor() çağrılabilir
                    }
                });
        // Analog sinyal otursun diye kamerayı ~2 sn sonra aç (CallOutgoingDoor gibi)
        new android.os.Handler(getMainLooper()).postDelayed(() -> { if (call != null) call.start(); }, 2000);

        // Küçük görüntüye (veya büyük görüntüye) dokununca büyük-küçük yer değişir
        View.OnClickListener swap = v -> { if (call != null) call.swapRenderers(); };
        remote.setOnClickListener(swap);
        local.setOnClickListener(swap);

        // Kapıyı Aç (monitör röleyi kendisi sürer)
        android.view.View openBtn = findViewById(com.multitek.smarthome.R.id.mdOpenDoor);
        if (openBtn != null) openBtn.setOnClickListener(v -> {
            try { com.multitek.i2c.I2CUtil.openDoor(); } catch (Throwable ignored) {}
            try { com.multitek.i2c.I2CUtil2.openDoor(); } catch (Throwable ignored) {}
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (call != null) { call.hangup(); call = null; }
        // Analog kapı hattını kapat
        try {
            com.multitek.i2c.I2CUtil.closeAnalogConnection();
            com.multitek.i2c.I2CUtil2.closeAnalogConnection();
            com.multitek.i2c.I2CUtil.setVoiceDefault("MobilDiafonDoor");
            com.multitek.i2c.I2CUtil2.setVoiceDefault("MobilDiafonDoor");
        } catch (Throwable ignored) {}
    }
}

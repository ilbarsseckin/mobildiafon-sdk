package com.multitek.smarthome.mobildiafon;

import com.mobildiafon.rtc.MobilDiafonReceiver;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.multitek.i2c.I2CUtil;
import com.multitek.i2c.I2CUtil2;
import com.multitek.smarthome.activities.intercom.CameraPreview;

import org.webrtc.SurfaceViewRenderer;

/**
 * QR ziyaretçi (A senaryosu) monitör gelen-çağrı ekranı.
 * İKİ görüntü: kapı paneli kamerası (camera 0, CameraPreview) + misafir telefon
 * kamerası (WebRTC). Küçük görüntüye dokununca büyük/küçük geçişi (switch).
 *
 * Kapı kamerası + I2C = HOST tarafı (cihaza özel). SDK yalnızca WebRTC + sinyal sağlar.
 * R importunu kendi paketinle değiştir: com.multitek.smarthome.R
 */
public class MobilDiafonIncomingActivity extends Activity {

    private SurfaceViewRenderer remote;   // misafir WebRTC
    private FrameLayout doorCamHolder;    // kapı paneli kamerası container
    private CameraPreview doorCam;        // camera 0 önizleme
    private MobilDiafonReceiver receiver;
    private boolean answered = false;
    private boolean remoteBig = false;    // switch durumu

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(com.multitek.smarthome.R.layout.activity_mobildiafon_qr_incoming);

        doorCamHolder = findViewById(com.multitek.smarthome.R.id.mdDoorCam);
        remote        = findViewById(com.multitek.smarthome.R.id.mdRemote);
        TextView txtCaller = findViewById(com.multitek.smarthome.R.id.mdCaller);
        Button btnAnswer  = findViewById(com.multitek.smarthome.R.id.mdAnswer);
        Button btnReject  = findViewById(com.multitek.smarthome.R.id.mdReject);
        Button btnOpen    = findViewById(com.multitek.smarthome.R.id.mdOpenDoor);

        remote.setZOrderMediaOverlay(true); // küçük görüntü kapı kamerasının ÜSTÜnde

        String callerName = getIntent().getStringExtra("callerName");
        txtCaller.setText(callerName != null ? callerName : "Ziyaretçi");

        // ---- KAPI PANELİ KAMERASI (camera 0) — HOST tarafı ----
        // Analog kapı video hattını aç ki camera 0 sinyal alsın (CallOutgoingDoor gibi).
        // NOT: SES WebRTC'den gidiyor; burada analog ses yolunu AÇMIYORUZ (çakışmasın).
        // Kapı kamerası siyah gelirse setVoiceMonitorSpeak() satırlarını aç.
        int block = 1;
        try { block = com.multitek.smarthome.utils.MyUtils.getInstance().getDeviceProperties().getBlock(); } catch (Throwable ignored) {}
        final int fblock = block;
        try { I2CUtil.callToAnalogDoor(fblock, 0, 1); I2CUtil2.callToAnalogDoor(fblock, 0, 1); } catch (Throwable ignored) {}
        // Kapı kamerası siyahsa aşağıyı aç:
        // try { I2CUtil.setVoiceMonitorSpeak(); I2CUtil2.setVoiceMonitorSpeak(); } catch (Throwable ignored) {}

        // Analog sinyal otursun diye kamerayı ~1.5 sn sonra ekle
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                doorCam = new CameraPreview(this);
                doorCamHolder.removeAllViews();
                doorCamHolder.addView(doorCam);
            } catch (Throwable ignored) {}
        }, 1500);

        // ---- MİSAFİR WebRTC (callee) ----
        receiver = MobilDiafonReceiver.get();
        receiver.setCallListener(new MobilDiafonReceiver.Listener() {
            @Override public void onIncoming(String c, String n) { }
            @Override public void onConnected() { }
            @Override public void onEnded(String reason) { runOnUiThread(() -> finish()); }
        });

        btnAnswer.setOnClickListener(v -> {
            if (answered) return;
            answered = true;
            // Monitörden cevaplandı -> ses WebRTC (cloud) üzerinden
            try {
                I2CUtil.setVoiceDefault("md");  I2CUtil2.setVoiceDefault("md");
                I2CUtil.setVoiceCloudSpeak();   I2CUtil.monitorAnswered();  I2CUtil.mobilAnswered();
                I2CUtil2.setVoiceCloudSpeak();  I2CUtil2.monitorAnswered(); I2CUtil2.mobilAnswered();
            } catch (Throwable ignored) {}
            receiver.answer(remote);   // misafir videosunu al, ses gönder
        });

        btnReject.setOnClickListener(v -> { receiver.reject(); finish(); });

        btnOpen.setOnClickListener(v -> {
            new Handler(Looper.getMainLooper()).postDelayed(I2CUtil::openDoor, 300);
            new Handler(Looper.getMainLooper()).postDelayed(I2CUtil2::openDoor, 300);
            receiver.hangup();
            finish();
        });

        // ---- SWITCH: küçük görüntüye dokununca büyük/küçük yer değiştir ----
        remote.setOnClickListener(v -> toggleSwap());
        doorCamHolder.setOnClickListener(v -> { if (remoteBig) toggleSwap(); });
    }

    private void toggleSwap() {
        remoteBig = !remoteBig;
        FrameLayout.LayoutParams big = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        int wpx = (int) (220 * getResources().getDisplayMetrics().density);
        int hpx = (int) (300 * getResources().getDisplayMetrics().density);
        // remote'u büyüt/küçült
        android.view.ViewGroup.LayoutParams rp = remote.getLayoutParams();
        if (remoteBig) { rp.width = FrameLayout.LayoutParams.MATCH_PARENT; rp.height = FrameLayout.LayoutParams.MATCH_PARENT; remote.setZOrderMediaOverlay(false); }
        else           { rp.width = wpx; rp.height = hpx; remote.setZOrderMediaOverlay(true); }
        remote.setLayoutParams(rp);
        remote.bringToFront();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiver != null && receiver.isInCall()) receiver.hangup();
        MobilDiafonReceiver.get().setCallListener(null);
        // Kapı kamerası + analog hattı kapat
        try { if (doorCam != null) doorCam.closeCamera(); } catch (Throwable ignored) {}
        try {
            I2CUtil.closeAnalogConnection();  I2CUtil2.closeAnalogConnection();
            I2CUtil.setVoiceDefault("md");    I2CUtil2.setVoiceDefault("md");
        } catch (Throwable ignored) {}
    }
}

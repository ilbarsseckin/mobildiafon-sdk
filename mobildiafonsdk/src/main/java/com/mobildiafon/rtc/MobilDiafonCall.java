package com.mobildiafon.rtc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * MobilDiafon — Akıllı Diafon (monitör) ÇAĞIRAN (caller) motoru.
 * =============================================================================
 * DOĞRU MODEL (backend'e dokunmadan, canlı sözleşmeyle birebir):
 *   1) POST /auth/guest-token { qrToken }         -> GUEST jwt
 *   2) socket'e GUEST olarak bağlan (auth.token)  -> sakinin presence'ını EZMEZ
 *   3) emit 'call:start-flat' { apartmentId, source:'qr' }
 *      -> backend dairenin TÜM sakinlerine 'call:incoming' yollar (TELEFON çalar)
 *   4) 'call:accepted' { callId, accepterId } gelir -> offer üret
 *      webrtc:offer { toUserId, callId, sdp } / webrtc:answer / webrtc:ice
 *
 * NEDEN GUEST: cihaz sakin token'ıyla bağlanırsa backend her user için tek
 * socket tuttuğundan sakinin telefonunun yerini alır ve 'call:incoming'
 * cihaza döner (telefon çalmaz). Guest ayrı kimlik -> telefon çalar.
 *
 * Kamera 0 tekil-açılır: bu motor aktifken eski CameraPreview AÇILMAZ.
 */
public class MobilDiafonCall {

    private static final String TAG = "MobilDiafonCall";
    private static String socketUrl() { return MobilDiafonConfig.get().socketUrl; }

    public interface Listener {
        void onState(String state);   // "connecting","ringing","connected","ended:<reason>","error:<msg>"
        void onRemoteOpenDoor();      // karşı taraf "kapıyı aç" derse (opsiyonel)
    }

    private static List<PeerConnection.IceServer> iceServers() {
        return MobilDiafonConfig.get().iceServers();
    }

    private static String apiBase() { return MobilDiafonConfig.get().apiBase; }

    private final Context appCtx;
    private final String apartmentId;      // aranacak daire
    private String buildingQrToken;        // guest-token için (verilirse manager yerine bunu kullan)
    private final SurfaceViewRenderer localView;
    private final SurfaceViewRenderer remoteView;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Socket socket;
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private VideoCapturer capturer;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private SurfaceTextureHelper surfaceHelper;

    private VideoTrack remoteVideoTrack;   // karşı taraf (telefon) görüntüsü
    private boolean swapped = false;

    private String callId;
    private String peerUserId;
    private boolean ended = false;

    /** Kapıdan giden arama aktifken true. Receiver, bu sırada gelen kendi
     *  call:incoming'ini yok saysın (monitör kendini ikinci kez çalmasın). */
    public static volatile boolean OUTGOING_ACTIVE = false;

    public MobilDiafonCall(Context ctx, String apartmentId,
                           SurfaceViewRenderer localView, SurfaceViewRenderer remoteView,
                           Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.apartmentId = apartmentId;
        this.localView = localView;
        this.remoteView = remoteView;
        this.listener = listener;
    }

    /** QR/door-start akışı: buildingQrToken doğrudan verilir (manager'a bağlı değil). */
    public MobilDiafonCall(Context ctx, String apartmentId, String buildingQrToken,
                           SurfaceViewRenderer localView, SurfaceViewRenderer remoteView,
                           Listener listener) {
        this(ctx, apartmentId, localView, remoteView, listener);
        this.buildingQrToken = buildingQrToken;
    }

    // ============================ başlat ============================

    public void start() {
        if (apartmentId == null || apartmentId.isEmpty()) {
            emitState("error:daire bağlı değil (apartmentId yok)");
            return;
        }
        OUTGOING_ACTIVE = true;
        try {
            eglBase = EglBase.create();
            initRenderers();
            initFactory();
            createPeerConnection();
            startCamera();       // kapı kamerası (camera 0) -> monitör + karşıya
            addAudio();
            emitState("connecting");
        } catch (Exception e) {
            Log.e(TAG, "start error", e);
            emitState("error:" + e.getMessage());
            hangup();
            return;
        }
        // GUEST token al -> socket'e bağlan (ağ işi arka thread'de)
        io.execute(() -> {
            try {
                String guestToken = (buildingQrToken != null && !buildingQrToken.isEmpty())
                        ? guestTokenFromQr(buildingQrToken)                       // door-start akışı
                        : MobilDiafonManager.get(appCtx).getGuestTokenSync();     // aktivasyonlu akış
                main.post(() -> connectSocket(guestToken));
            } catch (Exception e) {
                emitState("error:guest-token: " + e.getMessage());
                main.post(this::hangup);
            }
        });
    }

    /** POST /auth/guest-token { qrToken } -> { token } (senkron, arka thread'den). */
    private String guestTokenFromQr(String qrToken) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(apiBase() + "/auth/guest-token").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000); conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] out = new JSONObject().put("qrToken", qrToken).toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (java.io.OutputStream os = conn.getOutputStream()) { os.write(out); }
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject res = sb.length() == 0 ? new JSONObject() : new JSONObject(sb.toString());
            String t = res.optString("token", "");
            if (t.isEmpty()) throw new Exception(res.optString("message", "guest-token alınamadı"));
            return t;
        } finally { conn.disconnect(); }
    }

    private void initRenderers() {
        if (localView != null) {
            localView.init(eglBase.getEglBaseContext(), null);
            localView.setEnableHardwareScaler(true);
        }
        if (remoteView != null) {
            remoteView.init(eglBase.getEglBaseContext(), null);
            remoteView.setEnableHardwareScaler(true);
        }
    }

    private void initFactory() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appCtx).createInitializationOptions());
        DefaultVideoEncoderFactory enc =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory dec =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(enc)
                .setVideoDecoderFactory(dec)
                .createPeerConnectionFactory();
    }

    private void createPeerConnection() {
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers());
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate c) {
                if (socket == null || callId == null) return;
                try {
                    JSONObject cand = new JSONObject()
                            .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex);
                    socket.emit("webrtc:ice", new JSONObject()
                            .put("toUserId", peerUserId).put("callId", callId).put("candidate", cand));
                } catch (Exception e) { Log.e(TAG, "ice emit", e); }
            }
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                if (receiver.track() instanceof VideoTrack) {
                    final VideoTrack vt = (VideoTrack) receiver.track();
                    remoteVideoTrack = vt;
                    final SurfaceViewRenderer target = swapped ? localView : remoteView;
                    if (target != null) main.post(() -> { try { vt.addSink(target); } catch (Exception ignored) {} });
                }
            }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                if (s == PeerConnection.PeerConnectionState.CONNECTED) emitState("connected");
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onAddStream(MediaStream s) {}
            @Override public void onRemoveStream(MediaStream s) {}
            @Override public void onDataChannel(org.webrtc.DataChannel d) {}
            @Override public void onRenegotiationNeeded() {}
        });
    }

    private void startCamera() {
        CameraEnumerator en = new Camera1Enumerator(false);
        String[] names = en.getDeviceNames();
        if (names.length == 0) { emitState("error:kamera yok"); return; }
        String target = names[0];   // kapı görüntüsü index 0'daki kamerada

        capturer = en.createCapturer(target, new CameraVideoCapturer.CameraEventsHandler() {
            @Override public void onCameraError(String s) { Log.e(TAG, "cam err " + s); }
            @Override public void onCameraDisconnected() {}
            @Override public void onCameraFreezed(String s) {}
            @Override public void onCameraOpening(String s) {}
            @Override public void onFirstFrameAvailable() {}
            @Override public void onCameraClosed() {}
        });
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoSource = factory.createVideoSource(capturer.isScreencast());
        capturer.initialize(surfaceHelper, appCtx, videoSource.getCapturerObserver());
        // Analog kapı kamerası V4L2, gerçek boyutu 1024x600 (logcat: surfaceChanged w=1024 h=600).
        capturer.startCapture(1024, 600, 15);

        localVideoTrack = factory.createVideoTrack("md_video", videoSource);
        if (localView != null) localVideoTrack.addSink(localView);
        pc.addTrack(localVideoTrack, java.util.Collections.singletonList("md_stream"));
    }

    private void addAudio() {
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("md_audio", audioSource);
        pc.addTrack(localAudioTrack, java.util.Collections.singletonList("md_stream"));
    }

    // ============================ sinyalleşme ============================

    private void connectSocket(String guestToken) {
        try {
            IO.Options o = new IO.Options();
            o.transports = new String[]{"websocket"};
            o.forceNew = true;
            o.auth = new java.util.HashMap<>();
            o.auth.put("token", guestToken);   // GUEST kimliği
            socket = IO.socket(socketUrl(), o);

            socket.on(Socket.EVENT_CONNECT, a -> emitStartFlat());

            socket.on("call:accepted", a -> {
                JSONObject d = arg0(a);
                if (d != null) {
                    callId = d.optString("callId", callId);
                    String acc = d.optString("accepterId", null);
                    if (acc != null && !acc.isEmpty()) peerUserId = acc;
                }
                // Karşı taraf (telefon) cevapladı: host panele "cevaplandı" desin
                // (I2CUtil.monitorAnswered vs.). SDK donanıma dokunmaz, sadece bildirir.
                emitState("accepted");
                createOffer();
            });
            socket.on("webrtc:answer", a -> {
                JSONObject d = arg0(a);
                if (d == null) return;
                JSONObject sdp = d.optJSONObject("sdp");
                if (sdp == null) return;
                pc.setRemoteDescription(new SimpleSdp("setRemote(answer)"),
                        new SessionDescription(SessionDescription.Type.ANSWER, sdp.optString("sdp")));
            });
            socket.on("webrtc:ice", a -> {
                JSONObject d = arg0(a);
                if (d == null || pc == null) return;
                JSONObject c = d.optJSONObject("candidate");
                if (c == null) return;
                pc.addIceCandidate(new IceCandidate(
                        c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate")));
            });

            socket.on("call:ringing",     a -> emitState("ringing"));
            socket.on("call:ended",       a -> end("ended"));
            socket.on("call:rejected",    a -> end("rejected"));
            socket.on("call:taken",       a -> end("taken"));
            socket.on("call:unavailable", a -> {
                JSONObject d = arg0(a);
                end(d != null ? d.optString("reason", "unavailable") : "unavailable");
            });

            socket.connect();
        } catch (Exception e) {
            Log.e(TAG, "connectSocket", e);
            emitState("error:socket " + e.getMessage());
            hangup();
        }
    }

    /** Daireyi ara (backend: onCallStartFlat). source:'qr' -> 'both' modda da geçer. */
    private void emitStartFlat() {
        try {
            emitState("ringing");
            socket.emit("call:start-flat", new JSONObject()
                    .put("apartmentId", apartmentId)
                    .put("source", "qr"));   // konum modundaki binalarda lat/lng gerekebilir (bkz. not)
        } catch (Exception e) { Log.e(TAG, "start-flat emit", e); }
    }

    private void createOffer() {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        pc.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SimpleSdp("setLocal(offer)"), sdp);
                try {
                    socket.emit("webrtc:offer", new JSONObject()
                            .put("toUserId", peerUserId).put("callId", callId)
                            .put("sdp", new JSONObject().put("sdp", sdp.description).put("type", "offer")));
                } catch (Exception e) { Log.e(TAG, "offer emit", e); }
            }
            @Override public void onCreateFailure(String s) { emitState("error:offer " + s); }
            @Override public void onSetSuccess() {}
            @Override public void onSetFailure(String s) {}
        }, c);
    }

    // ============================ kontroller ============================

    public void setMicMuted(boolean muted) {
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
    }

    /** İki görüntüyü (kapı kamerası / telefon) büyük-küçük yer değiştir. */
    public void swapRenderers() {
        if (localView == null || remoteView == null) return;
        main.post(() -> {
            try {
                if (localVideoTrack != null) { localVideoTrack.removeSink(localView); localVideoTrack.removeSink(remoteView); }
                if (remoteVideoTrack != null) { remoteVideoTrack.removeSink(localView); remoteVideoTrack.removeSink(remoteView); }
                swapped = !swapped;
                // localView = büyük, remoteView = küçük (layout'ta öyle)
                if (!swapped) {
                    if (localVideoTrack != null) localVideoTrack.addSink(localView);    // kapı büyük
                    if (remoteVideoTrack != null) remoteVideoTrack.addSink(remoteView); // telefon küçük
                } else {
                    if (remoteVideoTrack != null) remoteVideoTrack.addSink(localView);  // telefon büyük
                    if (localVideoTrack != null) localVideoTrack.addSink(remoteView);   // kapı küçük
                }
            } catch (Exception ignored) {}
        });
    }

    public void hangup() {
        try { if (socket != null && callId != null)
            socket.emit("call:end", new JSONObject().put("callId", callId)); } catch (Exception ignored) {}
        end("local");
    }

    private synchronized void end(String reason) {
        if (ended) return;
        ended = true;
        OUTGOING_ACTIVE = false;
        main.post(() -> { if (listener != null) listener.onState("ended:" + reason); });
        release();
    }

    private void release() {
        try { if (capturer != null) { capturer.stopCapture(); capturer.dispose(); } } catch (Exception ignored) {}
        try { if (surfaceHelper != null) surfaceHelper.dispose(); } catch (Exception ignored) {}
        try { if (videoSource != null) videoSource.dispose(); } catch (Exception ignored) {}
        try { if (audioSource != null) audioSource.dispose(); } catch (Exception ignored) {}
        try { if (pc != null) pc.close(); } catch (Exception ignored) {}
        try { if (factory != null) factory.dispose(); } catch (Exception ignored) {}
        try { if (localView != null) localView.release(); } catch (Exception ignored) {}
        try { if (remoteView != null) remoteView.release(); } catch (Exception ignored) {}
        try { if (socket != null) { socket.disconnect(); socket.close(); } } catch (Exception ignored) {}
        try { if (eglBase != null) eglBase.release(); } catch (Exception ignored) {}
        pc = null; factory = null; socket = null;
    }

    // ============================ yardımcılar ============================

    private void emitState(final String s) { main.post(() -> { if (listener != null) listener.onState(s); }); }

    private static JSONObject arg0(Object[] a) {
        if (a != null && a.length > 0 && a[0] instanceof JSONObject) return (JSONObject) a[0];
        return null;
    }

    private static class SimpleSdp implements SdpObserver {
        private final String tag;
        SimpleSdp(String t) { this.tag = t; }
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() { Log.d(TAG, tag + " ok"); }
        @Override public void onCreateFailure(String s) { Log.e(TAG, tag + " createFail " + s); }
        @Override public void onSetFailure(String s) { Log.e(TAG, tag + " setFail " + s); }
    }
}

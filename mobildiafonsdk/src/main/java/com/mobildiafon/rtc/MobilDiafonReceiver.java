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
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * MobilDiafon — Akıllı Diafon (monitör) ALICI (callee) motoru.
 * =============================================================================
 * Monitör, mobildiafon.com'a token'la sürekli bağlı durur ve KENDİ dairesine
 * gelen çağrıyı (QR ziyaretçi / panel) yakalar. Ziyaretçi videosunu alır,
 * monitör mikrofonunu geri gönderir (sadece ses -> kapı kamerası çakışmaz).
 * Kapı açma monitörde YERELdir: Activity I2CUtil.openDoor() çağırır.
 *
 * Sinyalleşme (doğrulanmış JS SDK ile birebir):
 *   call:incoming { callId, caller:{id,name,photoUrl}, buildingId }
 *   -> call:accept { callId }
 *   -> webrtc:offer { sdp:{sdp,type} }  (gelir)
 *   -> webrtc:answer { toUserId, callId, sdp:{sdp,type} }  (gönderilir)
 *   -> webrtc:ice çift yönlü
 *   call:ended / call:taken -> biter
 *
 * Singleton: Service içinde start() ile ayakta tutulur.
 */
public class MobilDiafonReceiver {

    private static final String TAG = "MobilDiafonRecv";
    // URL / ICE / ses hızları MobilDiafonConfig'ten gelir (varsayılanlar aynı).
    private static String socketUrl() { return MobilDiafonConfig.get().socketUrl; }

    private static List<PeerConnection.IceServer> iceServers() {
        return MobilDiafonConfig.get().iceServers();
    }

    /** Servis/UI'ye olay bildirimi. */
    public interface Listener {
        void onIncoming(String callId, String callerName);  // ekranı aç
        void onConnected();                                  // medya bağlandı
        void onEnded(String reason);
        /** QR ile kapı çağrısı: monitör kapı kamerasıyla daireyi arasın (caller). */
        default void onDoorStart(String apartmentId, String buildingQrToken) {}
        /** Ev sahibi telefondan "Kapıyı Aç" dedi: monitör röleyi sürsün (I2CUtil.openDoor). */
        default void onDoorOpen(String apartmentId) {}
        /** Çağrıyı DAİREDEN (telefon) biri cevapladı: monitör ses/donanımını buna göre ayarlasın. */
        default void onPhoneAnswered(String callId) {}
        /** C: telefon canlı kapı görüntüsü istedi -> host analog hattı açsın (camera 0 sinyali). */
        default void onDoorViewStart() {}
        /** C: canlı görüntü bitti -> host analog hattı kapatsın. */
        default void onDoorViewStop() {}
    }

    private static MobilDiafonReceiver sInstance;
    public static synchronized MobilDiafonReceiver get() {
        if (sInstance == null) sInstance = new MobilDiafonReceiver();
        return sInstance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private Context appCtx;
    private String token;
    private Listener listener;       // Service (kalıcı): onIncoming
    private Listener callListener;   // Activity (geçici): onConnected/onEnded

    private Socket socket;
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;

    private SurfaceViewRenderer remoteView;   // Activity açılınca bağlanır
    private VideoTrack pendingRemoteTrack;

    private String callId;
    private String peerUserId;
    private boolean inCall = false;

    // ---- C: canlı kapı görüntüsü (door-view) — ayrı pc, video-only ----
    private PeerConnection pcView;
    private VideoSource viewSource;
    private VideoTrack viewTrack;
    private CameraVideoCapturer viewCapturer;
    private SurfaceTextureHelper viewHelper;
    private String viewPeerUserId;
    private boolean viewActive = false;

    public boolean isConnected() { return socket != null && socket.connected(); }
    public boolean isInCall()    { return inCall; }
    public String  getCallId()   { return callId; }

    /** Service kalıcı listener (onIncoming). */
    public void setListener(Listener l) { this.listener = l; }
    /** Activity geçici listener (onConnected/onEnded). Destroy'da null ver. */
    public void setCallListener(Listener l) { this.callListener = l; }

    // ---- socket'i ayakta tut (Service.onCreate) ----
    public synchronized void start(Context ctx, String token) {
        this.appCtx = ctx.getApplicationContext();
        this.token = token;
        if (socket != null && socket.connected()) return;
        try {
            // NOT: WebRTC (PeerConnectionFactory/EglBase) burada BAŞLATILMAZ.
            // Linphone'un yerel medya kütüphaneleriyle çakışıp süreci düşürüyor.
            // WebRTC yalnızca gerçek çağrı olunca (answer / MobilDiafonCall) kurulur.
            IO.Options o = new IO.Options();
            o.transports = new String[]{"websocket"};
            o.reconnection = true;
            o.forceNew = false;
            o.auth = new java.util.HashMap<>();
            o.auth.put("token", token);
            o.query = "token=" + token;
            socket = IO.socket(socketUrl(), o);

            socket.on(Socket.EVENT_CONNECT, a -> Log.d(TAG, "socket connected"));
            socket.on(Socket.EVENT_DISCONNECT, a -> Log.d(TAG, "socket disconnected"));

            socket.on("call:incoming", a -> {
                JSONObject d = arg0(a);
                if (d == null || inCall) return;              // meşgulse (v1) yoksay
                if (MobilDiafonCall.OUTGOING_ACTIVE) return;  // kapıdan biz arıyoruz -> kendi çağrımız, yok say
                callId = d.optString("callId", null);
                JSONObject caller = d.optJSONObject("caller");
                peerUserId = caller != null ? caller.optString("id", null) : d.optString("callerUserId", null);
                String name = caller != null ? caller.optString("name", "Ziyaretçi") : "Ziyaretçi";
                inCall = true;
                final String fn = name;
                main.post(() -> { if (listener != null) listener.onIncoming(callId, fn); });
            });

            socket.on("webrtc:offer", a -> {
                JSONObject d = arg0(a);
                if (d == null) return;
                JSONObject sdp = d.optJSONObject("sdp");
                if (sdp == null) return;
                onOffer(sdp.optString("sdp"));
            });
            socket.on("webrtc:ice", a -> {
                JSONObject d = arg0(a);
                if (d == null) return;
                JSONObject c = d.optJSONObject("candidate");
                if (c == null) return;
                IceCandidate cand = new IceCandidate(
                        c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"));
                String cid = d.optString("callId", "");
                if ("view".equals(cid)) { if (pcView != null) pcView.addIceCandidate(cand); }
                else if (pc != null)    { pc.addIceCandidate(cand); }
            });

            // C: door-view cevabı (telefon answer'ı) -> pcView
            socket.on("webrtc:answer", a -> {
                JSONObject d = arg0(a);
                if (d == null || pcView == null) return;
                JSONObject sdp = d.optJSONObject("sdp");
                if (sdp == null) return;
                pcView.setRemoteDescription(new SimpleSdp("view setRemote(answer)"),
                        new SessionDescription(SessionDescription.Type.ANSWER, sdp.optString("sdp")));
            });
            socket.on("call:ended",    a -> end("ended"));
            socket.on("call:rejected", a -> end("rejected"));
            socket.on("call:taken",    a -> end("taken"));

            // Çağrı DAİREDEN (telefon) cevaplandı -> monitör donanım/ses ayarı (I2C).
            // call:taken'dan AYRI: sadece gerçek cevaplamada gelir (zaman aşımında GELMEZ).
            socket.on("call:answered", a -> {
                JSONObject d = arg0(a);
                final String cid = d != null ? d.optString("callId", null) : null;
                main.post(() -> { if (listener != null) listener.onPhoneAnswered(cid); });
            });

            // QR kapı çağrısı tetikleyicisi (backend door-ring -> bu cihaz)
            socket.on("door:start", a -> {
                JSONObject d = arg0(a);
                final String apt = d != null ? d.optString("apartmentId", null) : null;
                final String bqr = d != null ? d.optString("buildingQrToken", null) : null;
                main.post(() -> { if (listener != null) listener.onDoorStart(apt, bqr); });
            });

            // Ev sahibi telefondan kapı açma (backend door-open -> bu monitör)
            // Çağrı sırasında OLMASA da çalışır (socket hep açık).
            socket.on("door:open", a -> {
                JSONObject d = arg0(a);
                final String apt = d != null ? d.optString("apartmentId", null) : null;
                main.post(() -> { if (listener != null) listener.onDoorOpen(apt); });
            });

            // C: telefon canlı kapı görüntüsü istedi (backend door:view -> bu monitör)
            socket.on("door:view", a -> {
                JSONObject d = arg0(a);
                final String viewer = d != null ? d.optString("viewerUserId", null) : null;
                main.post(() -> startDoorView(viewer));
            });
            socket.on("door:view-stop", a -> main.post(() -> stopDoorView()));

            socket.connect();
        } catch (Exception e) {
            Log.e(TAG, "start error", e);
        }
    }

    // ---- çağrıyı kabul et (Activity "Cevapla") ----
    public void answer(SurfaceViewRenderer remote) {
        ensureFactory();   // WebRTC'yi burada (çağrı anında) kur
        setupAudioRouting();  // Fortemedia: iletişim modu + hoparlör
        this.remoteView = remote;
        if (eglBase != null && remoteView != null) {
            remoteView.init(eglBase.getEglBaseContext(), null);
            remoteView.setEnableHardwareScaler(true);
        }
        createPeerConnection();
        addAudio();                       // sadece ses gönder (kamera çakışmaz)
        // Bekleyen uzak track varsa bağla
        if (pendingRemoteTrack != null && remoteView != null) {
            final VideoTrack vt = pendingRemoteTrack;
            main.post(() -> { try { vt.addSink(remoteView); } catch (Exception ignored) {} });
        }
        try {
            socket.emit("call:accept", new JSONObject().put("callId", callId));
        } catch (Exception e) { Log.e(TAG, "accept emit", e); }
    }

    public void reject() {
        try { if (socket != null && callId != null)
            socket.emit("call:reject", new JSONObject().put("callId", callId)); } catch (Exception ignored) {}
        end("local");
    }

    public void hangup() {
        try { if (socket != null && callId != null)
            socket.emit("call:end", new JSONObject().put("callId", callId)); } catch (Exception ignored) {}
        end("local");
    }

    public void setMicMuted(boolean muted) {
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
    }

    // ============================ WebRTC ============================

    // Ses hızları config'ten (varsayılan 16000/16000). Robotluk sürerse config.inputSampleRate=48000 dene.
    private static int fmOutputRate() { return MobilDiafonConfig.get().outputSampleRate; }
    private static int fmInputRate()  { return MobilDiafonConfig.get().inputSampleRate; }

    private void ensureFactory() {
        if (factory != null) return;
        eglBase = EglBase.create();
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appCtx).createInitializationOptions());
        DefaultVideoEncoderFactory enc =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory dec =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        // ---- SES: Fortemedia gömülü DSP ile uyum ----
        // Cas-cus'un sebebi: WebRTC 48kHz açmaya çalışıyor, Fortemedia yolu 16kHz;
        // resample+buffer kayması = cızırtı. Ayrıca donanım AEC'yi (Fortemedia) WebRTC
        // kendi yazılım AEC'siyle üst üste açıyor. Bunu elle kilitliyoruz.
        org.webrtc.audio.JavaAudioDeviceModule adm =
                org.webrtc.audio.JavaAudioDeviceModule.builder(appCtx)
                        .setInputSampleRate(fmInputRate())             // mikrofon (cızırtı bunun uyumundan)
                        .setOutputSampleRate(fmOutputRate())           // hoparlör (temiz yön)
                        .setUseHardwareAcousticEchoCanceler(true)      // Fortemedia donanım AEC (senin testinde giriş sesi bununla düzeldi)
                        .setUseHardwareNoiseSuppressor(true)
                        .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION) // donanım AEC bu kaynakla çalışır; MIC ile mikrofon boş kalıyor
                        .setAudioRecordErrorCallback(new org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback() {
                            @Override public void onWebRtcAudioRecordInitError(String e) { Log.e(TAG, "AudioRecord init: " + e); }
                            @Override public void onWebRtcAudioRecordStartError(org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode c, String e) { Log.e(TAG, "AudioRecord start: " + e); }
                            @Override public void onWebRtcAudioRecordError(String e) { Log.e(TAG, "AudioRecord: " + e); }
                        })
                        .setAudioTrackErrorCallback(new org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback() {
                            @Override public void onWebRtcAudioTrackInitError(String e) { Log.e(TAG, "AudioTrack init: " + e); }
                            @Override public void onWebRtcAudioTrackStartError(org.webrtc.audio.JavaAudioDeviceModule.AudioTrackStartErrorCode c, String e) { Log.e(TAG, "AudioTrack start: " + e); }
                            @Override public void onWebRtcAudioTrackError(String e) { Log.e(TAG, "AudioTrack: " + e); }
                        })
                        .createAudioDeviceModule();

        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(enc)
                .setVideoDecoderFactory(dec)
                .createPeerConnectionFactory();
    }

    /** Çağrı anında ses yönlendirmesini iletişim moduna al. answer() içinde çağrılır. */
    private void setupAudioRouting() {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(true);
            }
        } catch (Throwable t) { Log.e(TAG, "audio routing", t); }
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
                    pendingRemoteTrack = vt;
                    if (remoteView != null) main.post(() -> { try { vt.addSink(remoteView); } catch (Exception ignored) {} });
                }
            }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                if (s == PeerConnection.PeerConnectionState.CONNECTED)
                    main.post(() -> { if (callListener != null) callListener.onConnected(); });
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

    private void addAudio() {
        // Fortemedia donanımı AEC/AGC/NS'yi zaten yapıyor. WebRTC'nin YAZILIM işlemesini
        // kapatıyoruz — açık kalırsa konuşurken çift işleme (AGC pompalama + AEC gating)
        // "cazur cuzur" bozulmaya yol açıyor.
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"));
        audioSource = factory.createAudioSource(c);
        localAudioTrack = factory.createAudioTrack("md_audio", audioSource);
        pc.addTrack(localAudioTrack, java.util.Collections.singletonList("md_stream"));
    }

    private void onOffer(String sdpStr) {
        if (pc == null) return;
        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, sdpStr);
        pc.setRemoteDescription(new SdpObserver() {
            @Override public void onSetSuccess() {
                MediaConstraints c = new MediaConstraints();
                c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
                pc.createAnswer(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription answer) {
                        pc.setLocalDescription(new SimpleSdp("setLocal(answer)"), answer);
                        try {
                            socket.emit("webrtc:answer", new JSONObject()
                                    .put("toUserId", peerUserId).put("callId", callId)
                                    .put("sdp", new JSONObject().put("sdp", answer.description).put("type", "answer")));
                        } catch (Exception e) { Log.e(TAG, "answer emit", e); }
                    }
                    @Override public void onCreateFailure(String s) { Log.e(TAG, "answer fail " + s); }
                    @Override public void onSetSuccess() {}
                    @Override public void onSetFailure(String s) {}
                }, c);
            }
            @Override public void onSetFailure(String s) { Log.e(TAG, "setRemote fail " + s); }
            @Override public void onCreateSuccess(SessionDescription sdp) {}
            @Override public void onCreateFailure(String s) {}
        }, offer);
    }

    // ============================ C: canlı kapı görüntüsü ============================

    /** Telefon canlı görüntü istedi: analog hattı aç (host I2C), camera 0'ı video-only yayınla. */
    private void startDoorView(String viewerUserId) {
        if (inCall) return;                 // görüşmedeyken canlı izleme yok (kamera çakışır)
        if (viewActive) stopDoorView();     // önceki izlemeyi kapat
        if (viewerUserId == null) return;
        viewPeerUserId = viewerUserId;
        ensureFactory();

        // Host analog hattı açsın (callToAnalogDoor) ki camera 0 sinyal alsın — I2C host tarafında.
        if (listener != null) listener.onDoorViewStart();

        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers());
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        pcView = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate c) {
                if (socket == null || viewPeerUserId == null) return;
                try {
                    JSONObject cand = new JSONObject()
                            .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex);
                    socket.emit("webrtc:ice", new JSONObject()
                            .put("toUserId", viewPeerUserId).put("callId", "view").put("candidate", cand));
                } catch (Exception e) { Log.e(TAG, "view ice emit", e); }
            }
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                Log.d(TAG, "doorView pc=" + s);
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

        // camera 0 (kapı paneli) yakala — MobilDiafonCall ile aynı
        try {
            CameraEnumerator en = new Camera1Enumerator(false);
            String[] names = en.getDeviceNames();
            if (names.length == 0) { Log.e(TAG, "doorView: kamera yok"); stopDoorView(); return; }
            viewCapturer = en.createCapturer(names[0], new CameraVideoCapturer.CameraEventsHandler() {
                @Override public void onCameraError(String s) { Log.e(TAG, "view cam err " + s); }
                @Override public void onCameraDisconnected() {}
                @Override public void onCameraFreezed(String s) {}
                @Override public void onCameraOpening(String s) {}
                @Override public void onFirstFrameAvailable() {}
                @Override public void onCameraClosed() {}
            });
            viewHelper = SurfaceTextureHelper.create("ViewCapture", eglBase.getEglBaseContext());
            viewSource = factory.createVideoSource(viewCapturer.isScreencast());
            viewCapturer.initialize(viewHelper, appCtx, viewSource.getCapturerObserver());
            viewCapturer.startCapture(1024, 600, 15);   // analog V4L2 gerçek boyutu
            viewTrack = factory.createVideoTrack("md_view", viewSource);
            pcView.addTrack(viewTrack, java.util.Collections.singletonList("md_view_stream"));
        } catch (Throwable t) { Log.e(TAG, "doorView camera", t); stopDoorView(); return; }

        // sadece video gönderiyoruz -> offer
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        pcView.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription offer) {
                pcView.setLocalDescription(new SimpleSdp("view setLocal(offer)"), offer);
                try {
                    socket.emit("webrtc:offer", new JSONObject()
                            .put("toUserId", viewPeerUserId).put("callId", "view")
                            .put("sdp", new JSONObject().put("sdp", offer.description).put("type", "offer")));
                } catch (Exception e) { Log.e(TAG, "view offer emit", e); }
            }
            @Override public void onCreateFailure(String s) { Log.e(TAG, "view offer fail " + s); }
            @Override public void onSetSuccess() {}
            @Override public void onSetFailure(String s) {}
        }, c);

        viewActive = true;
    }

    private synchronized void stopDoorView() {
        if (!viewActive && pcView == null) return;
        viewActive = false;
        try { if (viewCapturer != null) viewCapturer.stopCapture(); } catch (Throwable ignored) {}
        try { if (viewCapturer != null) viewCapturer.dispose(); } catch (Throwable ignored) {}
        try { if (viewSource != null) viewSource.dispose(); } catch (Throwable ignored) {}
        try { if (viewHelper != null) viewHelper.dispose(); } catch (Throwable ignored) {}
        try { if (pcView != null) pcView.close(); } catch (Throwable ignored) {}
        viewCapturer = null; viewSource = null; viewTrack = null; viewHelper = null;
        pcView = null; viewPeerUserId = null;
        // Host analog hattı kapatsın (I2C)
        if (listener != null) listener.onDoorViewStop();
    }

    private synchronized void end(String reason) {
        boolean was = inCall;
        inCall = false;
        try { if (pc != null) pc.close(); } catch (Exception ignored) {}
        try { if (audioSource != null) audioSource.dispose(); } catch (Exception ignored) {}
        try { if (remoteView != null) remoteView.release(); } catch (Exception ignored) {}
        pc = null; audioSource = null; localAudioTrack = null;
        remoteView = null; pendingRemoteTrack = null;
        callId = null; peerUserId = null;
        // Ses modunu normale al (Linphone/normal diafon sesini serbest bırak)
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) { am.setMode(android.media.AudioManager.MODE_NORMAL); am.setSpeakerphoneOn(false); }
        } catch (Throwable ignored) {}
        if (was) main.post(() -> { if (callListener != null) callListener.onEnded(reason); });
        // socket AÇIK kalır -> bir sonraki çağrıyı bekler
    }

    /** Servis kapanırken (nadiren) socket'i kapat. */
    public synchronized void shutdown() {
        end("shutdown");
        try { if (socket != null) { socket.disconnect(); socket.close(); } } catch (Exception ignored) {}
        try { if (factory != null) factory.dispose(); } catch (Exception ignored) {}
        try { if (eglBase != null) eglBase.release(); } catch (Exception ignored) {}
        socket = null; factory = null; eglBase = null;
    }

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

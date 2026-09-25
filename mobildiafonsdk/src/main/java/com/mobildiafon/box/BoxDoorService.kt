package com.mobildiafon.box

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import io.socket.client.IO
import io.socket.client.Socket

/**
 * Kalıcı "kapı cihazı" bağlantısı (Adım A).
 *
 * Box socket'te SÜREKLİ açık kalır, backend'e kendini bildirir (box:register),
 * ve sakin "kapıyı izle" deyince backend'ten gelen `door:view {viewerUserId}` ile
 * analog kapı kamerasını **video-only ikinci akış** (callId="view") olarak o sakine
 * yollar. Uygulama bu "view" akışını zaten ikinci ekranda gösteriyor (akıllı
 * diafondaki gibi) — app'e değişiklik gerekmez.
 *
 * Çağrı YOKken çalışır (kamera o an boşta). Box'ın kendi çağrısı (BoxCall) sırasında
 * door-view başlatılmaz — tek kamera çakışmasın.
 */
class BoxDoorService internal constructor(
    ctx: Context,
    private val cfg: BoxConfig,
    private val api: BoxApi
) {
    companion object {
        private const val TAG = "DiafonBoxDoorSvc"
        private const val MAX_VIEW_MS = 45_000L   // analog hatti uzun tutma: 45 sn sonra otomatik kapat
        private const val WATCHDOG_MS = 30_000L   // socket sagligini 30 sn'de bir kontrol et
    }

    private val stopViewRunnable = Runnable { stopDoorView() }

    private val appCtx = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var socket: Socket? = null
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pcView: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var viewTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var viewerUserId: String? = null
    @Volatile private var running = false
    @Volatile private var reconnecting = false

    // WATCHDOG: her 30 sn socket bagli mi diye bakar; kopuksa YENI token cekip yeniden baglanir.
    // Token suresi dolsa da (30g) ya da socket takilsa da box kendini toparlar -> "cevrimdisi" kalmaz.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                if (socket?.connected() != true && !reconnecting) {
                    Log.d(TAG, "watchdog: socket kopuk -> yeni token ile yeniden baglan")
                    reconnectFresh()
                }
            } catch (_: Exception) {}
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val token = api.guestTokenSync()   // binanın guest token'ı
                main.post { connect(token); main.postDelayed(watchdog, WATCHDOG_MS) }
            } catch (e: Exception) { Log.e(TAG, "start", e); running = false }
        }.start()
    }

    /** Kopuk socket'i tazele: yeni guest token al, eski socket'i kapat, yeniden bagla (register EVENT_CONNECT'te). */
    private fun reconnectFresh() {
        if (reconnecting) return
        reconnecting = true
        Thread {
            try {
                val token = api.guestTokenSync()
                main.post {
                    try { socket?.off(); socket?.disconnect(); socket?.close() } catch (_: Exception) {}
                    socket = null
                    connect(token)
                    reconnecting = false
                }
            } catch (e: Exception) { Log.e(TAG, "reconnectFresh", e); reconnecting = false }
        }.start()
    }

    private fun connect(guestToken: String) {
        try {
            val o = IO.Options()
            o.transports = arrayOf("websocket")
            o.reconnection = true
            o.auth = hashMapOf("token" to guestToken)
            socket = IO.socket(cfg.socketUrl, o)

            socket!!.on(Socket.EVENT_CONNECT) {
                try {
                    socket!!.emit("box:register", JSONObject()
                        .put("buildingId", api.buildingId)
                        .put("mac", DiafonBox.getDeviceId())
                        .put("block", DiafonBox.getBlock())   // cok bloklu binada dogru kapiyi acmak icin
                        .put("door", DiafonBox.getDoor()))
                    Log.d(TAG, "box baglandi + register: bina=${api.buildingId} blok=${DiafonBox.getBlock()}")
                } catch (_: Exception) {}
            }

            socket!!.on("door:view") { a ->
                val d = arg0(a); val viewer = d?.optString("viewerUserId", null)
                main.post { startDoorView(viewer) }
            }
            socket!!.on("door:view-stop") { main.post { stopDoorView() } }

            // Door-view sirasinda "Kapiyi Ac": bina bazli gelir -> global role (setRelay).
            socket!!.on("call:open-door") { main.post { DiafonBox.fireRelay() } }

            socket!!.on("webrtc:answer") { a ->
                val d = arg0(a) ?: return@on
                if (d.optString("callId", "") != "view") return@on
                val sdp = d.optJSONObject("sdp") ?: return@on
                pcView?.setRemoteDescription(SimpleSdp("view answer"),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp.optString("sdp")))
            }
            socket!!.on("webrtc:ice") { a ->
                val d = arg0(a) ?: return@on
                if (d.optString("callId", "") != "view") return@on
                val c = d.optJSONObject("candidate") ?: return@on
                pcView?.addIceCandidate(IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate")))
            }

            socket!!.connect()
        } catch (e: Exception) { Log.e(TAG, "connect", e) }
    }

    /** door:view geldi: analog kapı kamerasını video-only "view" akışıyla viewer'a yolla. */
    private fun startDoorView(viewer: String?) {
        if (viewer == null) return
        // Analog hat TEK kaynak: cagri varsa ya da baska izleme varsa -> mesgul.
        val busy = DiafonBox.busyReason
        if (busy != null && busy != "view") {
            try { socket?.emit("door:view-busy", JSONObject().put("viewerUserId", viewer).put("reason", busy)) } catch (_: Exception) {}
            Log.d(TAG, "door-view reddedildi (mesgul: $busy)")
            return
        }
        stopDoorView()
        DiafonBox.busyReason = "view"
        viewerUserId = viewer
        main.postDelayed(stopViewRunnable, MAX_VIEW_MS)   // 45 sn sonra otomatik birak
        // ÖNCE analog kapi hattini ac (host: callToAnalogDoor) -> camera 0 sinyal alsin.
        // WebRTC negotiation zaten ~1 sn surdugunden analog hat oturur.
        DiafonBox.fireDoorViewStart()
        try {
            eglBase = EglBase.create()
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appCtx).createInitializationOptions()
            )
            val enc = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
            val dec = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(enc).setVideoDecoderFactory(dec).createPeerConnectionFactory()

            val c = PeerConnection.RTCConfiguration(cfg.iceServers())
            c.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            c.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            pcView = factory!!.createPeerConnection(c, object : PeerConnection.Observer {
                override fun onIceCandidate(cand: IceCandidate) {
                    try {
                        val j = JSONObject().put("candidate", cand.sdp)
                            .put("sdpMid", cand.sdpMid).put("sdpMLineIndex", cand.sdpMLineIndex)
                        socket?.emit("webrtc:ice", JSONObject()
                            .put("toUserId", viewerUserId).put("callId", "view").put("candidate", j))
                    } catch (_: Exception) {}
                }
                override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>?) {}
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                    // Sakin izlemeyi kapatinca WebRTC duser -> analog hatti HEMEN birak (45sn bekleme)
                    if (s == PeerConnection.PeerConnectionState.DISCONNECTED ||
                        s == PeerConnection.PeerConnectionState.FAILED ||
                        s == PeerConnection.PeerConnectionState.CLOSED) {
                        main.post { stopDoorView() }
                    }
                }
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            })

            val en = Camera1Enumerator(false)
            val names = en.deviceNames
            if (names.isEmpty()) { Log.e(TAG, "kamera yok"); stopDoorView(); return }
            capturer = en.createCapturer(names[0], object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(s: String?) { Log.e(TAG, "cam err $s") }
                override fun onCameraDisconnected() {}
                override fun onCameraFreezed(s: String?) {}
                override fun onCameraOpening(s: String?) {}
                override fun onFirstFrameAvailable() {}
                override fun onCameraClosed() {}
            })
            surfaceHelper = SurfaceTextureHelper.create("BoxViewCap", eglBase!!.eglBaseContext)
            videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
            capturer!!.initialize(surfaceHelper, appCtx, videoSource!!.capturerObserver)
            capturer!!.startCapture(1024, 600, 15)
            viewTrack = factory!!.createVideoTrack("box_view", videoSource)
            pcView!!.addTrack(viewTrack, listOf("box_view_stream"))

            val mc = MediaConstraints()
            mc.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mc.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            pcView!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pcView?.setLocalDescription(SimpleSdp("view setLocal"), sdp)
                    try {
                        socket?.emit("webrtc:offer", JSONObject()
                            .put("toUserId", viewerUserId).put("callId", "view")
                            .put("sdp", JSONObject().put("sdp", sdp.description).put("type", "offer")))
                    } catch (_: Exception) {}
                }
                override fun onCreateFailure(s: String?) { Log.e(TAG, "view offer fail $s") }
                override fun onSetSuccess() {}
                override fun onSetFailure(s: String?) {}
            }, mc)
            Log.d(TAG, "door-view basladi -> viewer=$viewer")
        } catch (e: Exception) { Log.e(TAG, "startDoorView", e); stopDoorView() }
    }

    private fun stopDoorView() {
        main.removeCallbacks(stopViewRunnable)
        if (DiafonBox.busyReason == "view") DiafonBox.fireDoorViewStop()   // analog hatti kapat
        try { capturer?.stopCapture(); capturer?.dispose() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { pcView?.close() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { eglBase?.release() } catch (_: Exception) {}
        capturer = null; surfaceHelper = null; videoSource = null; viewTrack = null
        pcView = null; factory = null; eglBase = null; viewerUserId = null
        if (DiafonBox.busyReason == "view") DiafonBox.busyReason = null   // hatti birak
    }

    fun stop() {
        running = false
        main.removeCallbacks(watchdog)
        stopDoorView()
        try { socket?.disconnect(); socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun arg0(a: Array<Any>?): JSONObject? =
        if (a != null && a.isNotEmpty() && a[0] is JSONObject) a[0] as JSONObject else null

    private inner class SimpleSdp(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription?) {}
        override fun onSetSuccess() { Log.d(TAG, "$tag ok") }
        override fun onCreateFailure(s: String?) { Log.e(TAG, "$tag fail $s") }
        override fun onSetFailure(s: String?) { Log.e(TAG, "$tag setfail $s") }
    }
}

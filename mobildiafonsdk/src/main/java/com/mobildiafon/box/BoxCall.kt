package com.mobildiafon.box

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import io.socket.client.IO
import io.socket.client.Socket
import java.util.concurrent.Executors

/**
 * DiafonBox ÇAĞIRAN (caller) motoru — v1.2.0 çalışan sürümü (her çağrıda kamera
 * kendi açılır). Canlı sözleşmeyle birebir: guest-token -> socket(GUEST) ->
 * call:start-flat -> webrtc:offer/answer/ice. Sadece arar.
 */
class BoxCall internal constructor(
    ctx: Context,
    private val apartmentId: String,
    private val cfg: BoxConfig,
    private val api: BoxApi,
    private val localView: SurfaceViewRenderer?,
    private val remoteView: SurfaceViewRenderer?,
    private val listener: BoxListener?
) {
    companion object { private const val TAG = "DiafonBoxCall" }

    private val appCtx = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var socket: Socket? = null
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var swapped = false

    private var callId: String? = null
    private var peerUserId: String? = null
    private var ended = false

    // ============================ başlat ============================
    fun start() {
        if (apartmentId.isEmpty()) { emitState("error:apartmentId yok"); return }
        try {
            eglBase = EglBase.create()
            initRenderers()
            initFactory()
            createPeerConnection()
            startCamera()
            addAudio()
            emitState("connecting")
        } catch (e: Exception) {
            Log.e(TAG, "start error", e); emitState("error:" + e.message); hangup(); return
        }
        io.execute {
            try {
                val guestToken = api.guestTokenSync()
                main.post { connectSocket(guestToken) }
            } catch (e: Exception) {
                emitState("error:guest-token: " + e.message); main.post { hangup() }
            }
        }
    }

    private fun initRenderers() {
        localView?.apply { init(eglBase!!.eglBaseContext, null); setEnableHardwareScaler(true) }
        remoteView?.apply { init(eglBase!!.eglBaseContext, null); setEnableHardwareScaler(true) }
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appCtx).createInitializationOptions()
        )
        val enc = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val dec = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(enc)
            .setVideoDecoderFactory(dec)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection() {
        val c = PeerConnection.RTCConfiguration(cfg.iceServers())
        c.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        c.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        pc = factory!!.createPeerConnection(c, object : PeerConnection.Observer {
            override fun onIceCandidate(cand: IceCandidate) {
                val s = socket ?: return; val cid = callId ?: return
                try {
                    val j = JSONObject().put("candidate", cand.sdp)
                        .put("sdpMid", cand.sdpMid).put("sdpMLineIndex", cand.sdpMLineIndex)
                    s.emit("webrtc:ice", JSONObject().put("toUserId", peerUserId).put("callId", cid).put("candidate", j))
                } catch (e: Exception) { Log.e(TAG, "ice emit", e) }
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>?) {
                val tr = receiver.track()
                if (tr is VideoTrack) {
                    remoteVideoTrack = tr
                    val target = if (swapped) localView else remoteView
                    target?.let { t -> main.post { try { tr.addSink(t) } catch (_: Exception) {} } }
                }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                if (s == PeerConnection.PeerConnectionState.CONNECTED) emitState("connected")
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
    }

    private fun startCamera() {
        val en = Camera1Enumerator(false)
        val names = en.deviceNames
        if (names.isEmpty()) { emitState("error:kamera yok"); return }
        capturer = en.createCapturer(names[0], object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(s: String?) { Log.e(TAG, "cam err $s") }
            override fun onCameraDisconnected() {}
            override fun onCameraFreezed(s: String?) {}
            override fun onCameraOpening(s: String?) {}
            override fun onFirstFrameAvailable() {}
            override fun onCameraClosed() {}
        })
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
        capturer!!.initialize(surfaceHelper, appCtx, videoSource!!.capturerObserver)
        capturer!!.startCapture(1024, 600, 15)
        localVideoTrack = factory!!.createVideoTrack("box_video", videoSource)
        localView?.let { localVideoTrack!!.addSink(it) }
        pc!!.addTrack(localVideoTrack, listOf("box_stream"))
    }

    private fun addAudio() {
        audioSource = factory!!.createAudioSource(MediaConstraints())
        localAudioTrack = factory!!.createAudioTrack("box_audio", audioSource)
        pc!!.addTrack(localAudioTrack, listOf("box_stream"))
    }

    // ============================ sinyalleşme ============================
    private fun connectSocket(guestToken: String) {
        try {
            val o = IO.Options()
            o.transports = arrayOf("websocket")
            o.forceNew = true
            o.auth = hashMapOf("token" to guestToken)
            socket = IO.socket(cfg.socketUrl, o)

            socket!!.on(Socket.EVENT_CONNECT) { emitStartFlat() }

            socket!!.on("call:accepted") { a ->
                arg0(a)?.let { d ->
                    callId = d.optString("callId", callId)
                    d.optString("accepterId", "").takeIf { it.isNotEmpty() }?.let { peerUserId = it }
                }
                emitState("accepted")
                createOffer()
            }
            socket!!.on("webrtc:answer") { a ->
                val d = arg0(a) ?: return@on
                val sdp = d.optJSONObject("sdp") ?: return@on
                pc?.setRemoteDescription(SimpleSdp("setRemote(answer)"),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp.optString("sdp")))
            }
            socket!!.on("webrtc:ice") { a ->
                val d = arg0(a) ?: return@on
                val c = d.optJSONObject("candidate") ?: return@on
                pc?.addIceCandidate(IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate")))
            }

            socket!!.on("call:ringing") { emitState("ringing") }
            socket!!.on("call:ended") { end("ended") }
            socket!!.on("call:rejected") { end("rejected") }
            socket!!.on("call:taken") { end("taken") }
            socket!!.on("call:unavailable") { a ->
                val d = arg0(a); end(d?.optString("reason", "unavailable") ?: "unavailable")
            }

            // KAPI AÇ: backend aktif çağrıda arayan kutuya call:open-door yollarsa röle.
            socket!!.on("call:open-door") {
                main.post { listener?.onOpenDoor(); DiafonBox.fireRelay() }
            }

            socket!!.connect()
        } catch (e: Exception) {
            Log.e(TAG, "connectSocket", e); emitState("error:socket " + e.message); hangup()
        }
    }

    private fun emitStartFlat() {
        try {
            emitState("ringing")
            socket!!.emit("call:start-flat", JSONObject().put("apartmentId", apartmentId).put("source", "qr"))
        } catch (e: Exception) { Log.e(TAG, "start-flat emit", e) }
    }

    private fun createOffer() {
        val c = MediaConstraints()
        c.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        c.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        pc?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(SimpleSdp("setLocal(offer)"), sdp)
                try {
                    socket?.emit("webrtc:offer", JSONObject()
                        .put("toUserId", peerUserId).put("callId", callId)
                        .put("sdp", JSONObject().put("sdp", sdp.description).put("type", "offer")))
                } catch (e: Exception) { Log.e(TAG, "offer emit", e) }
            }
            override fun onCreateFailure(s: String?) { emitState("error:offer $s") }
            override fun onSetSuccess() {}
            override fun onSetFailure(s: String?) {}
        }, c)
    }

    // ============================ kontroller ============================
    fun setMicMuted(muted: Boolean) { localAudioTrack?.setEnabled(!muted) }

    fun swapRenderers() {
        val lv = localView ?: return; val rv = remoteView ?: return
        main.post {
            try {
                localVideoTrack?.let { it.removeSink(lv); it.removeSink(rv) }
                remoteVideoTrack?.let { it.removeSink(lv); it.removeSink(rv) }
                swapped = !swapped
                if (!swapped) {
                    localVideoTrack?.addSink(lv); remoteVideoTrack?.addSink(rv)
                } else {
                    remoteVideoTrack?.addSink(lv); localVideoTrack?.addSink(rv)
                }
            } catch (_: Exception) {}
        }
    }

    fun hangup() {
        try { if (socket != null && callId != null) socket!!.emit("call:end", JSONObject().put("callId", callId)) } catch (_: Exception) {}
        end("local")
    }

    @Synchronized
    private fun end(reason: String) {
        if (ended) return
        ended = true
        main.post { listener?.onState("ended:$reason") }
        release()
    }

    private fun release() {
        try { capturer?.stopCapture(); capturer?.dispose() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { localView?.release() } catch (_: Exception) {}
        try { remoteView?.release() } catch (_: Exception) {}
        try { socket?.disconnect(); socket?.close() } catch (_: Exception) {}
        try { eglBase?.release() } catch (_: Exception) {}
        pc = null; factory = null; socket = null; remoteVideoTrack = null
    }

    // ============================ yardımcılar ============================
    private fun emitState(s: String) { main.post { listener?.onState(s) } }

    private fun arg0(a: Array<Any>?): JSONObject? =
        if (a != null && a.isNotEmpty() && a[0] is JSONObject) a[0] as JSONObject else null

    private inner class SimpleSdp(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() { Log.d(TAG, "$tag ok") }
        override fun onCreateFailure(s: String?) { Log.e(TAG, "$tag createFail $s") }
        override fun onSetFailure(s: String?) { Log.e(TAG, "$tag setFail $s") }
    }
}

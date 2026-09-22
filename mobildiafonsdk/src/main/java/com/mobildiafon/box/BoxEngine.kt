package com.mobildiafon.box

import android.content.Context
import android.util.Log
import org.webrtc.*

/**
 * Paylaşılan, "sıcak" WebRTC motoru. EglBase + PeerConnectionFactory + kapı kamerası
 * + mikrofon BİR KEZ kurulur ve çağrılar arası açık tutulur.
 *
 * Amaç HIZ: her çağrıda kamera/motor sıfırdan açılmaz -> ilk kare neredeyse anında.
 * [DiafonBox.prewarm] boşta bunu kurar; [BoxCall] hazır track'leri yeni bir
 * PeerConnection'a ekler ve çağrı bitince motoru DEĞİL sadece pc/socket'i kapatır.
 */
internal class BoxEngine private constructor(appCtx: Context) {

    val eglBase: EglBase = EglBase.create()
    val factory: PeerConnectionFactory
    val videoTrack: VideoTrack
    val audioTrack: AudioTrack

    private val capturer: VideoCapturer
    private val videoSource: VideoSource
    private val audioSource: AudioSource
    private val surfaceHelper: SurfaceTextureHelper

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appCtx).createInitializationOptions()
        )
        val enc = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val dec = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(enc)
            .setVideoDecoderFactory(dec)
            .createPeerConnectionFactory()

        // Kapı kamerası (index 0) — bir kez aç, açık tut
        val en = Camera1Enumerator(false)
        val names = en.deviceNames
        capturer = en.createCapturer(names[0], object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(s: String?) { Log.e(TAG, "cam err $s") }
            override fun onCameraDisconnected() {}
            override fun onCameraFreezed(s: String?) {}
            override fun onCameraOpening(s: String?) {}
            override fun onFirstFrameAvailable() {}
            override fun onCameraClosed() {}
        })
        surfaceHelper = SurfaceTextureHelper.create("BoxCapture", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceHelper, appCtx, videoSource.capturerObserver)
        // Native 1024x600, fps 15->25 (daha akici). Analog kaynak native oldugu icin
        // cozunurlugu buyutmek detay katmaz; netlik bitrate ile artar (bkz. BoxCall).
        capturer.startCapture(1024, 600, 25)
        videoTrack = factory.createVideoTrack("box_video", videoSource)
        videoTrack.setEnabled(true)

        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("box_audio", audioSource)
    }

    companion object {
        private const val TAG = "DiafonBoxEngine"

        @Volatile private var shared: BoxEngine? = null

        /** Motoru al (yoksa kur + kamerayı başlat). Ana thread'den çağrılmalı. */
        fun get(appCtx: Context): BoxEngine {
            return shared ?: synchronized(this) {
                shared ?: BoxEngine(appCtx.applicationContext).also { shared = it }
            }
        }

        fun isWarm(): Boolean = shared != null
    }
}

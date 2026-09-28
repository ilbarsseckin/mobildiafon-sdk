package com.mobildiafon.box

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * İZOLE TEŞHİS ARACI — üretim akışına dokunmaz, hiçbir yerden otomatik çağrılmaz.
 *
 * Amaç: FortyMedia analog hattının (ahize TX/RX) Android tarafında AYRI bir
 * ses cihazı / stream / mod üzerinden erişilebilir olup olmadığını bulmak.
 * API 23'te setCommunicationDevice yok ama AudioManager.getDevices() ve
 * AudioTrack/AudioRecord.setPreferredDevice() VAR (ikisi de API 23).
 *
 * Tetikleme (uygulamada kod/UI değişikliği gerekmez — DiafonBox.init kaydeder):
 *   adb shell am broadcast -a com.mobildiafon.AUDIO_PROBE [--es part all|dump|out|in]
 *   adb logcat -s AudioProbe
 * ya da kod içinden: DiafonBox.runAudioProbe("all")
 * Çağrı/izleme sürerken (busyReason != null) çalışmaz.
 *
 * Çıkış matrisi sırasında biri ahizeyi kulağında tutar, tonun duyulduğu
 * STEP numaralarını not eder. Giriş taramasında biri SADECE ahizeye konuşur
 * (kapı mikrofonu kapatılı/sessiz) — hangi kaynak yüksek dBFS veriyorsa
 * hat RX'i dijital olarak okunabiliyor demektir.
 *
 * Matrisi 2-3 farklı FortyMedia durumunda tekrar çalıştır (ör. 0x70 ahize açık,
 * mobilAnswered, monitorAnswered) — bazı HAL'ler yönlendirmeyi yalnızca hat
 * aktifken bağlar. Box uygulaması DiafonBox.setAudioProbeStateHook { i -> ... }
 * verirse her durum için matris otomatik tekrarlanır.
 */
internal class AudioRouteProbe(ctx: Context) {

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val tag = "AudioProbe"
    private val sr = 16000

    // ---------------------------------------------------------------- cihazlar
    fun typeName(t: Int): String = when (t) {
        0 -> "UNKNOWN"; 1 -> "BUILTIN_EARPIECE"; 2 -> "BUILTIN_SPEAKER"
        3 -> "WIRED_HEADSET"; 4 -> "WIRED_HEADPHONES"; 5 -> "LINE_ANALOG"
        6 -> "LINE_DIGITAL"; 7 -> "BT_SCO"; 8 -> "BT_A2DP"; 9 -> "HDMI"
        10 -> "HDMI_ARC"; 11 -> "USB_DEVICE"; 12 -> "USB_ACCESSORY"; 13 -> "DOCK"
        14 -> "FM"; 15 -> "BUILTIN_MIC"; 16 -> "FM_TUNER"; 17 -> "TV_TUNER"
        18 -> "TELEPHONY"; 19 -> "AUX_LINE"; 20 -> "IP"; 21 -> "BUS"
        else -> "TYPE_$t"
    }

    fun dumpDevices() {
        Log.i(tag, "===== DEVICES (mode=${am.mode} spk=${am.isSpeakerphoneOn}) =====")
        for (d in am.getDevices(AudioManager.GET_DEVICES_ALL)) {
            Log.i(tag, String.format(
                "%s id=%d type=%s name='%s' ch=%s sr=%s",
                if (d.isSink) "OUT" else "IN ", d.id, typeName(d.type), d.productName,
                d.channelCounts.joinToString(","), d.sampleRates.joinToString(",")
            ))
        }
        // Vendor HAL ipuçları (boş dönebilir, zararsız)
        for (k in listOf("routing", "audio_mode", "line_out", "aux", "fm_mode", "voice_path")) {
            Log.i(tag, "getParameters($k) = '${am.getParameters(k)}'")
        }
    }

    // ------------------------------------------------------------ çıkış matrisi
    private fun tone(ms: Int, hz: Double = 1000.0): ShortArray {
        val n = sr * ms / 1000
        return ShortArray(n) { i ->
            // 250 ms bip / 250 ms sessizlik — kulakla ayırt etmesi kolay
            if ((i * 4 / sr) % 2 == 0) (sin(2 * PI * hz * i / sr) * 20000).toInt().toShort() else 0
        }
    }

    private fun playOnce(stream: Int, dev: AudioDeviceInfo?, pcm: ShortArray): Boolean {
        var t: AudioTrack? = null
        return try {
            @Suppress("DEPRECATION")
            t = AudioTrack(stream, sr, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size * 2, AudioTrack.MODE_STATIC)
            t.write(pcm, 0, pcm.size)
            val prefOk = if (dev != null) t.setPreferredDevice(dev) else true
            t.play()
            Thread.sleep(pcm.size * 1000L / sr + 150)
            prefOk
        } catch (e: Exception) {
            Log.w(tag, "play error: $e"); false
        } finally {
            try { t?.stop() } catch (_: Exception) {}
            t?.release()
        }
    }

    fun outputMatrix() {
        val savedMode = am.mode
        val savedSpk = am.isSpeakerphoneOn
        val streams = linkedMapOf(
            "VOICE_CALL" to AudioManager.STREAM_VOICE_CALL,
            "MUSIC" to AudioManager.STREAM_MUSIC,
            "SYSTEM" to AudioManager.STREAM_SYSTEM
        )
        val savedVol = streams.values.associateWith { am.getStreamVolume(it) }
        val modes = linkedMapOf(
            "NORMAL" to AudioManager.MODE_NORMAL,
            "IN_COMMUNICATION" to AudioManager.MODE_IN_COMMUNICATION,
            "IN_CALL" to AudioManager.MODE_IN_CALL // MODIFY_PHONE_STATE yoksa yok sayılır; log'da gerçek mod görünür
        )
        val outs: List<AudioDeviceInfo?> =
            listOf<AudioDeviceInfo?>(null) + am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val pcm = tone(2000)
        var step = 0
        try {
            streams.values.forEach { am.setStreamVolume(it, am.getStreamMaxVolume(it), 0) }
            for ((mName, m) in modes) for (spk in listOf(false, true)) {
                am.mode = m
                am.isSpeakerphoneOn = spk
                Thread.sleep(300)
                for ((sName, s) in streams) for (d in outs) {
                    step++
                    val dn = d?.let { "${typeName(it.type)}#${it.id}" } ?: "default"
                    Log.i(tag, String.format(">>> STEP %02d  mode=%s(real=%d) spk=%b stream=%s dev=%s",
                        step, mName, am.mode, spk, sName, dn))
                    val ok = playOnce(s, d, pcm)
                    if (!ok) Log.w(tag, "    setPreferredDevice reddedildi")
                    Thread.sleep(1200) // not almak için boşluk
                }
            }
        } finally {
            am.mode = savedMode
            am.isSpeakerphoneOn = savedSpk
            savedVol.forEach { (s, v) -> am.setStreamVolume(s, v, 0) }
            Log.i(tag, "===== OUTPUT MATRIX DONE ($step adım), durum geri yüklendi =====")
        }
    }

    // ------------------------------------------------------------ giriş taraması
    fun inputScan(secondsEach: Int = 3) {
        val sources = linkedMapOf(
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "VOICE_CALL" to MediaRecorder.AudioSource.VOICE_CALL,       // genelde CAPTURE_AUDIO_OUTPUT ister
            "VOICE_DOWNLINK" to MediaRecorder.AudioSource.VOICE_DOWNLINK,
            "VOICE_UPLINK" to MediaRecorder.AudioSource.VOICE_UPLINK
        )
        val ins: List<AudioDeviceInfo?> =
            listOf<AudioDeviceInfo?>(null) + am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        Log.i(tag, "===== INPUT SCAN — şimdi SADECE AHİZEYE konuş =====")
        for ((sName, src) in sources) for (d in ins) {
            val dn = d?.let { "${typeName(it.type)}#${it.id}" } ?: "default"
            var r: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                r = AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    Log.i(tag, "IN src=$sName dev=$dn : INIT FAIL"); continue
                }
                if (d != null) r.setPreferredDevice(d)
                r.startRecording()
                val buf = ShortArray(sr / 10)
                var sumSq = 0.0; var n = 0L; var peak = 0
                val end = System.currentTimeMillis() + secondsEach * 1000L
                while (System.currentTimeMillis() < end) {
                    val k = r.read(buf, 0, buf.size)
                    for (i in 0 until k) {
                        val v = buf[i].toInt(); sumSq += v.toDouble() * v; n++
                        if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                    }
                }
                val rms = if (n > 0) sqrt(sumSq / n) else 0.0
                val db = if (rms > 0) 20 * log10(rms / 32768.0) else -120.0
                Log.i(tag, String.format("IN src=%-19s dev=%-22s rms=%6.1f dBFS peak=%d", sName, dn, db, peak))
            } catch (e: Exception) {
                Log.i(tag, "IN src=$sName dev=$dn : EXC $e")
            } finally {
                try { r?.stop() } catch (_: Exception) {}
                r?.release()
            }
        }
        Log.i(tag, "===== INPUT SCAN DONE =====")
    }

    /**
     * [part]: "all" | "dump" | "out" | "in".
     * [stateHook] (opsiyonel, box uygulamasından): i=0,1,2... için FortyMedia/I2C durumunu uygular
     * ve bir etiket döner; null dönerse durum listesi biter. Hook yoksa tek tur çalışır.
     */
    fun run(part: String = "all", stateHook: ((Int) -> String?)? = null) {
        dumpDevices()
        var i = 0
        while (true) {
            val label = if (stateHook != null) {
                try { stateHook(i) } catch (e: Exception) { Log.w(tag, "stateHook($i) hata: $e"); null }
            } else if (i == 0) "varsayilan" else null
            if (label == null) break
            Log.i(tag, "########## DURUM $i: $label ##########")
            Thread.sleep(500)
            if (part == "all" || part == "out") outputMatrix()
            if (part == "all" || part == "in") inputScan()
            i++
        }
        Log.i(tag, "########## PROBE BITTI ##########")
    }

    fun runAll() = run("all")
}

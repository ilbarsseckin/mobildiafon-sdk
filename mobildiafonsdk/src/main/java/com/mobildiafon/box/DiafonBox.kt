package com.mobildiafon.box

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.webrtc.SurfaceViewRenderer

/**
 * DiafonBox SDK — ekransız/analog-paralel kapı kutusu için TEK giriş noktası.
 *
 * Monitör alma (Receiver) ve kapı-görüntüle (door-view) YOKTUR. Kutu yalnızca:
 *   aktive olur  ->  daireyi arar  ->  WebRTC (kapı kamerası+ses)  ->  kapı rölesi
 *
 * KULLANIM (Application.onCreate):
 *   DiafonBox.init(this)                       // varsayilan config (mobildiafon.com)
 *   DiafonBox.setDeviceId(myMac())             // kutunun MAC'i (iki nokta yok, kucuk harf)
 *   DiafonBox.setRelay { Relay.open() }        // analog/GPIO role
 *   DiafonBox.activate { ok, msg -> ... }      // MAC panele eklendiyse ok=true
 *
 * ZIYARETCI DAIRE SECINCE:
 *   val call = DiafonBox.call(ctx, apartmentId, bigView, smallView, object: BoxListener {
 *       override fun onState(s: String) { ... }
 *       override fun onOpenDoor() { Relay.open() }   // BoxListener uzerinden de gelir
 *   })
 *   call.setMicMuted(true); call.swapRenderers(); call.hangup()
 */
object DiafonBox {

    fun interface Relay { fun openDoor() }

    private lateinit var appCtx: Context
    private var cfg: BoxConfig = BoxConfig()
    private var api: BoxApi? = null
    private var deviceId: String = ""
    private var relay: Relay? = null
    private val main = Handler(Looper.getMainLooper())

    /** SDK'yı başlat. [config] verilmezse mobildiafon.com canlı ayarları kullanılır. */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, config: BoxConfig = BoxConfig()) {
        appCtx = context.applicationContext
        cfg = config
        api = BoxApi(appCtx, cfg)
    }

    /** Kutunun kimliği = MAC (iki nokta olmadan, küçük harf). Aktivasyon buna göre eşleşir. */
    @JvmStatic
    fun setDeviceId(mac: String) {
        deviceId = mac.trim().lowercase().replace(":", "")
    }

    @JvmStatic
    fun getDeviceId(): String = deviceId

    /** Kapı rölesi (analog/GPIO). Sakin "Kapıyı Aç" deyince tetiklenir. */
    @JvmStatic
    fun setRelay(r: Relay) { relay = r }

    /** BoxCall tarafından çağrılır — global röleyi sürer. */
    internal fun fireRelay() { try { relay?.openDoor() } catch (_: Exception) {} }

    /**
     * POST /calls/box-activate { deviceId }. MAC panele bir binaya eklenmişse başarılı.
     * Sonuç ANA thread'de: onResult(ok, message). Başarılıysa daireler önbelleğe alınır.
     */
    @JvmStatic
    fun activate(onResult: (Boolean, String) -> Unit) {
        val a = api ?: run { onResult(false, "DiafonBox.init cagrilmadi"); return }
        if (deviceId.isEmpty()) { onResult(false, "MAC yok — once setDeviceId(...)"); return }
        Thread {
            val (ok, msg) = a.activateSync(deviceId)
            main.post { onResult(ok, msg) }
        }.start()
    }

    @JvmStatic fun isActive(): Boolean = api?.isActive == true
    @JvmStatic fun buildingId(): String = api?.buildingId ?: ""
    @JvmStatic fun buildingName(): String = api?.buildingName ?: ""

    /** Aktivasyonla önbelleğe alınan daire listesi (kutu UI'sinde göster). */
    @JvmStatic
    fun apartments(): List<Apartment> = api?.apartments() ?: emptyList()

    /** Aktivasyonu temizle (bina değişimi vb.). */
    @JvmStatic
    fun logout() { api?.clear() }

    /**
     * Bir daireyi ara. [localView] = kapı kamerası (büyük), [remoteView] = sakin görüntüsü (küçük).
     * Görüntü istemiyorsan view'ları null geç (sesli-yalnız değil; view yoksa yerel önizleme çizilmez).
     * Dönen [BoxCall] ile setMicMuted/swapRenderers/hangup kontrol edilir.
     */
    @JvmStatic
    @JvmOverloads
    fun call(
        ctx: Context,
        apartmentId: String,
        localView: SurfaceViewRenderer? = null,
        remoteView: SurfaceViewRenderer? = null,
        listener: BoxListener? = null
    ): BoxCall {
        val a = api ?: throw IllegalStateException("DiafonBox.init cagrilmadi")
        val call = BoxCall(ctx, apartmentId, cfg, a, localView, remoteView, listener)
        call.start()
        return call
    }
}

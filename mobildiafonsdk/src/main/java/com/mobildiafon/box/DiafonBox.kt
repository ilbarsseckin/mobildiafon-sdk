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

    /**
     * Door-view sırasında analog kapı hattını host yönetir: WebRTC camera 0'ı açmadan
     * ÖNCE host `I2CUtil.callToAnalogDoor(...)` ile kapı kamerasını camera 0'a bağlar;
     * bitince `closeAnalogConnection()` ile kapatır. Bu olmadan camera 0 sinyalsizdir.
     */
    interface DoorViewHost {
        fun onDoorViewStart()   // analog kapi hattini ac (callToAnalogDoor) -> camera 0 sinyal alsin
        fun onDoorViewStop()    // analog hatti kapat (closeAnalogConnection)
    }
    private var doorViewHost: DoorViewHost? = null
    @JvmStatic fun setDoorViewHost(h: DoorViewHost) { doorViewHost = h }
    internal fun fireDoorViewStart() { try { doorViewHost?.onDoorViewStart() } catch (_: Exception) {} }
    internal fun fireDoorViewStop()  { try { doorViewHost?.onDoorViewStop() } catch (_: Exception) {} }

    /**
     * Analog hat TEK kaynak: aynı anda ya bir çağrı ya bir door-view olabilir.
     * "call" | "view" | null. BoxCall ve BoxDoorService bunu paylaşır; biri meşgulse
     * diğeri başlamaz (bina genelinde tek kaynak — analog hattı kilitlemesin).
     */
    @Volatile internal var busyReason: String? = null

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

    /**
     * Kapı rölesini sürer — tüm donanım sırası (analog bağlantı + monitorAnswered + openDoor +
     * kapatma, gerekli gecikmelerle) box uygulamasının setRelay{...} lambdasında olmalıdır.
     * SDK burada sadece o lambdayı çağırır; kendi analog aç/kapa hamlesini YAPMAZ (çakışmasın).
     */
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

    /**
     * Kamerayı + WebRTC motorunu ÖNCEDEN aç (boşta çağır: activate sonrası, ya da
     * daire seçim ekranı açılınca). Böylece call() anında ilk kare neredeyse hemen gelir.
     * Arka thread'de çalışır; kamera bu andan itibaren açık kalır.
     */
    /**
     * NO-OP (v1.3.2). Paylaşılan sıcak motor görüntü/ses sorununa yol açtığı için
     * kaldırıldı; her çağrı kamerayı kendi açıyor (v1.2.0 çalışan davranışı).
     * API uyumu için bırakıldı — çağırman zararsız, bir şey yapmaz.
     */
    @JvmStatic
    fun prewarm(context: Context) { /* no-op */ }

    @JvmStatic fun isActive(): Boolean = api?.isActive == true
    @JvmStatic fun buildingId(): String = api?.buildingId ?: ""
    @JvmStatic fun buildingName(): String = api?.buildingName ?: ""

    /** Kutunun blogu/kapisi (panelde girilir, box-activate'ten gelir). callToAnalogDoor icin. */
    @JvmStatic fun getBlock(): Int = api?.block ?: 0
    @JvmStatic fun getDoor(): Int = api?.door ?: 0

    /** Kutunun marka/protokol tipi (box-activate'ten): 1=Multitek, 2=Audio/Netelsan, 0=belirsiz. */
    @JvmStatic fun getBoxType(): Int = api?.boxType ?: 0

    /** Aktivasyonla önbelleğe alınan daire listesi (kutu UI'sinde göster). */
    @JvmStatic
    fun apartments(): List<Apartment> = api?.apartments() ?: emptyList()

    /** Aktivasyonu temizle (bina değişimi vb.). */
    @JvmStatic
    fun logout() { api?.clear() }

    // ==================== Kapı servisi (door-view / geniş görüş) ====================
    private var doorService: BoxDoorService? = null

    /**
     * Kalıcı kapı bağlantısını başlat: box socket'te açık kalır, backend'e kaydolur
     * (box:register) ve sakin "kapıyı izle" deyince analog kapı kamerasını ikinci
     * ekrana (view akışı) yollar. activate() BAŞARILI olduktan sonra çağır.
     */
    @JvmStatic
    fun startDoorService(context: Context) {
        val a = api ?: return
        if (!a.isActive) { android.util.Log.w("DiafonBox", "startDoorService: kutu aktif degil"); return }
        // Kalici foreground service olarak calistir -> Android surecı oldurmez,
        // backend restart'inda socket saniyeler icinde yeniden baglanir.
        BoxForegroundService.start(context.applicationContext)
    }

    /** Foreground service tarafindan cagrilir: gercek socket'i baslatir (idempotent). */
    internal fun beginDoorSocket(context: Context) {
        val a = api ?: return
        if (!a.isActive) return
        if (doorService == null) doorService = BoxDoorService(context.applicationContext, cfg, a)
        doorService!!.start()
    }

    internal fun endDoorSocket() { doorService?.stop() }

    @JvmStatic
    fun stopDoorService() {
        try { if (::appCtx.isInitialized) BoxForegroundService.stop(appCtx) } catch (_: Exception) {}
        doorService?.stop()
    }

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

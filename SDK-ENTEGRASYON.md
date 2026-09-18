# MobilDiafon Android SDK — Entegrasyon

Akıllı diafon (monitör) için video-intercom motoru. WebRTC + Socket.io sinyalleşme,
kalıcı cihaz aktivasyonu ve üç senaryo (QR / panel / canlı kapı görüntüsü) bir kütüphanede.

> **Tasarım ilkesi:** `.aar` **donanımdan ve UI'dan bağımsızdır** — içinde sıfır
> `com.multitek.*` referansı vardır. Tüm I2C ve ekran işleri host uygulamaya
> `DiafonHost` köprüsüyle devredilir. Böylece SDK başka cihaz/markaya da taşınır.

---

## 1. İçerik

```
mobildiafon-sdk/
├── mobildiafonsdk/           ← KÜTÜPHANE MODÜLÜ (.aar üretir)
│   └── src/main/java/com/mobildiafon/rtc/
│       ├── MobilDiafon.java            (giriş noktası / facade)
│       ├── MobilDiafonConfig.java      (URL / ICE / ses hızları)
│       ├── DiafonHost.java             (donanım+UI köprüsü — host uygular)
│       ├── DeviceIdProvider.java       (cihaz kimliği — host verir: MAC)
│       ├── MobilDiafonManager.java     (token saklama + aktivasyon HTTP)
│       ├── MobilDiafonService.java     (kalıcı alıcı socket'i)
│       ├── MobilDiafonReceiver.java    (callee motoru: gelen çağrı + canlı görüntü)
│       └── MobilDiafonCall.java        (caller motoru: panelden daire arama)
│
└── reference-host/           ← .aar'A GİRMEZ. Host tarafına KOPYALANACAK örnekler.
    ├── java/com/multitek/smarthome/mobildiafon/
    │   ├── MultitekDiafonHost.java         (DiafonHost'un I2C implementasyonu)
    │   ├── MobilDiafonIncomingActivity.java (QR/panel gelen çağrı ekranı)
    │   └── MobilDiafonDoorActivity.java     (panelden arama ekranı)
    └── res/layout/
        ├── activity_mobildiafon_qr_incoming.xml
        └── activity_mobildiafon_incoming.xml
```

`.aar` = motor. `reference-host/` = Multitek'e özel yapıştırma katmanı (I2C, R, kamera).

---

## 2. Kütüphaneyi projeye ekleme

### settings.gradle
```gradle
include ':mobildiafonsdk'
project(':mobildiafonsdk').projectDir = new File('../mobildiafon-sdk/mobildiafonsdk')
// veya modülü uygulama projesinin içine kopyalayıp sadece: include ':mobildiafonsdk'
```

### Uygulama modülünün build.gradle
```gradle
dependencies {
    implementation project(':mobildiafonsdk')
}
```

`.aar` olarak da verebilirsin: `mobildiafonsdk` modülünü Android Studio'da
**Build → Make Module 'mobildiafonsdk'** ile derle → çıktı:
`mobildiafonsdk/build/outputs/aar/mobildiafonsdk-release.aar`
Bunu host projesinde `libs/` altına atıp `implementation files('libs/mobildiafonsdk-release.aar')`
diyebilirsin (bu durumda `stream-webrtc-android` ve `socket.io-client` bağımlılıklarını
host build.gradle'ına elle eklemen gerekir; modül olarak eklersen otomatik gelir).

---

## 3. Host tarafına kopyala (reference-host)

`reference-host/` altındaki 3 java + 2 layout'u host uygulamana koy:

- `MultitekDiafonHost.java` → I2C çağrılarını burada yapıyorsun (`I2CUtil.openDoor()` vb.).
- `MobilDiafonIncomingActivity.java`, `MobilDiafonDoorActivity.java` → host `R`'ını kullanır.
- layout xml'leri → `res/layout/`.

Bu üç dosya `com.multitek.smarthome.R`, `com.multitek.i2c.*`, `CameraPreview`,
`MyUtils` referansları içerir — host'ta derlenmeleri normaldir; **SDK içinde değildirler.**

Bu Activity'leri host `AndroidManifest.xml`'ine ekle:
```xml
<activity android:name=".mobildiafon.MobilDiafonIncomingActivity"
    android:launchMode="singleTop" android:excludeFromRecents="true"/>
<activity android:name=".mobildiafon.MobilDiafonDoorActivity"
    android:launchMode="singleTop" android:excludeFromRecents="true"/>
```

---

## 4. Başlatma (Application.onCreate)

```java
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();

        // Cihaz kimliği = MAC (backend'de "device-<mac>" kullanıcısı)
        MobilDiafon.setDeviceIdProvider(() ->
            MyUtils.getInstance().getMACAddress().replace(":", "").toLowerCase());

        // Donanım + ekran köprüsü
        MobilDiafon.setHost(new MultitekDiafonHost(getApplicationContext()));

        // (opsiyonel) sunucu/TURN değiştir:
        // MobilDiafon.config().socketUrl = "https://mobildiafon.com";

        // Aktive edilmişse kalıcı socket'i başlat
        MobilDiafon.start(this);
    }
}
```

> Not: Linphone medya çakışması nedeniyle socket `config().startDelayMs` (varsayılan 4 sn)
> gecikmeyle açılır. İlk saniyelerde telefon monitörü kısa süre "çevrimdışı" görebilir; normaldir.

---

## 5. Aktivasyon (kalıcılık)

Sakin, mobildiafon.com'da yönetici tarafından daireye eklenir. Monitörde OTP ile giriş
yapıp **kişi token'ını** aldıktan sonra:

```java
MobilDiafon.activate(ctx, personToken, apartmentId, "Monitor 20", (ok, msg) -> {
    if (ok) {
        MobilDiafon.start(ctx);   // artık kalıcı cihaz token'ıyla bağlanır
    } else {
        // msg: "Bu daire için yetkiniz yok" → numara o dairenin onaylı sakini değil
    }
});
```

Arka planda: `POST /calls/monitor-activate {apartmentId, deviceId}` (Bearer personToken)
→ backend `device-<deviceId>` kullanıcısı + onaylı sakin kurar, 3650 günlük **cihaz token'ı**
döner → `K_TOKEN`'a yazılır. Monitör bundan sonra sakinin telefonundan **ayrı** kullanıcı
olarak presence'a düşer (çakışma yok). Tekrar açılışta OTP gerekmez.

---

## 6. Senaryolar (hangi parça neyi yapar)

| Senaryo | Motor | Akış |
|--------|-------|------|
| **A — QR çağrısı** | `MobilDiafonReceiver` (callee) | Ziyaretçi QR okutur → backend daireye `call:incoming` → SDK `host.showIncoming()` → `IncomingActivity` kapı kamerası (camera 0) + ziyaretçi WebRTC'yi gösterir, `receiver.answer(remote)` ile cevaplanır. |
| **B — Panel çağrısı** | `MobilDiafonCall` (caller) | Panel `door:start` → SDK `host.showDoorCall()` → `DoorActivity` `MobilDiafonCall` ile daireyi arar (kapı kamerası telefona gider). Telefon cevaplayınca `onState("accepted")` → I2C `monitorAnswered`. |
| **C — Canlı kapı görüntüsü** | `MobilDiafonReceiver` (door-view) | Telefon `door:view-start` → SDK `receiver` camera 0'ı video-only yayınlar → `host.onDoorViewStart()` analog hattı açar (sessiz). Telefon "Kapı Aç" → `POST /calls/door-open` → `door:open` → `host.openDoor()`. |
| **Kapı açma** | — | Her zaman dairenin KENDİ monitörünün I2C'siyle: `host.openDoor()` → `I2CUtil.openDoor()`. Akıllı diafona özel; IP paneller ileride. |

Telefon cevapladığında (A/B) backend `call:answered` yollar → SDK `host.onPhoneAnswered()`
→ I2C `setVoiceCloudSpeak / monitorAnswered / mobilAnswered`.

---

## 7. DiafonHost — host'un uygulaması gereken köprü

| Metod | Ne zaman | Host ne yapar |
|-------|----------|---------------|
| `showIncoming(callId, name)` | A/panel gelen çağrı | Gelen-çağrı Activity'sini aç |
| `showDoorCall(apt, qr)` | B panel `door:start` | Arama Activity'sini aç |
| `openDoor()` | Telefondan "Kapı Aç" | `I2CUtil.openDoor()` |
| `onPhoneAnswered(callId)` | Telefon cevapladı | `setVoiceCloudSpeak/monitorAnswered/mobilAnswered` |
| `onDoorViewStart()` | C izleme başladı | `callToAnalogDoor(block,0,1)` (ses YOK) |
| `onDoorViewStop()` | C izleme bitti | `closeAnalogConnection()` |
| `onCallEnded(reason)` | Çağrı bitti | opsiyonel temizlik |

`showIncoming` dışında hepsinin varsayılan (boş) implementasyonu var; sadece kullandığını doldur.

---

## 8. Bağımlılıklar

- `io.getstream:stream-webrtc-android:1.1.1` (org.webrtc.*)
- `io.socket:socket.io-client:2.1.0` (io.socket.*, org.json, okhttp geçişli)
- `androidx.annotation:annotation`

minSdk **23** (Multitek A64, Android 6.0). Modül olarak eklersen bu bağımlılıklar
otomatik gelir; salt `.aar` kullanırsan host build.gradle'ına elle ekle.

---

## 9. Geliştirme sırası (sonraki adımlar)

Bu paket "çalışan çekirdek"tir. Üzerine planlananlar:
1. **B çağrı-içi kapı butonu** → `/calls/door-open {apartmentId}` bağlanması.
2. **Ses kalitesi** ince ayarı (`config().inputSampleRate` 48000 denemesi).
3. **Socket startup gecikmesi** — monitör bağlanınca telefona anlık "hazır" bilgisi.
4. Foreground service / reconnect dayanıklılığı.

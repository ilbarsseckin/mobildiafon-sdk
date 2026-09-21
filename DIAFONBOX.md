# DiafonBox — Ekransız Kapı Kutusu (SDK profili)

DiafonBox, ekranı olmayan bir **kapı kutusudur**. Bir daireye değil **binaya** bağlıdır,
admin panelde **MAC** ile aktive edilir. Ziyaretçi bir daire seçince kutu o dairenin
sakinlerini **görüntülü arar** ve sakin telefondan "Kapıyı Aç" deyince kutu **röleyi** açar.

Arama motoru monitörle **aynıdır** (`MobilDiafonCall`, `buildingQrToken` akışı). Fark:
- Aktivasyon **MAC → bina** (OTP/kişi token yok)
- Gelen çağrı UI'si yok (ekransız) — kutu sadece **arayan**
- `openDoor()` rölesi + daire listesi

Monitör akışını **bozmaz**; tamamen ek profildir.

---

## 1) SDK kullanımı (kutu tarafı)

### Application.onCreate
```java
// MAC = kutu kimliği (iki nokta yok, küçük harf)
MobilDiafon.setDeviceIdProvider(() -> myMac());   // ör. "a4cf1201ab90"

// Röle donanımı
MobilDiafonBox.setHost(new BoxHost() {
    @Override public void openDoor() { Relay.open(); }        // GPIO/röle
    @Override public void onCallEnded(String reason) { /* opsiyonel */ }
});

// Boot'ta aktive et (MAC panele eklenmişse başarılı)
MobilDiafonBox.activate(this, (ok, msg) -> {
    Log.d("box", "activate: " + ok + " " + msg);
});
```

### Ziyaretçi daire seçince (kutu UI'si)
```java
List<Apartment> flats = MobilDiafonBox.apartments(ctx);   // box-activate'ten
// ... kullanıcı bir daire seçti -> apartmentId

MobilDiafonCall call = MobilDiafonBox.call(ctx, apartmentId,
        localView,    // kapı kamerası (büyük)
        remoteView,   // sakin telefon görüntüsü (küçük)
        new MobilDiafonCall.Listener() {
            @Override public void onState(String s) {
                // "connecting","ringing","accepted","connected","ended:<reason>","error:<msg>"
            }
            @Override public void onRemoteOpenDoor() {
                // sakin "kapıyı aç" dedi -> host rölesi
                if (MobilDiafonBox.host() != null) MobilDiafonBox.host().openDoor();
            }
        });

// kontroller:
call.setMicMuted(true/false);
call.swapRenderers();
call.hangup();
```

> Kutu, binanın QR token'ıyla **guest** gibi bağlanır → backend dairenin tüm sakinlerine
> `call:incoming` yollar (telefonlar çalar). Sakin presence'ı ezilmez.

---

## 2) Backend'e eklenecek (NestJS) — GEREKLİ

SDK hazır; çalışması için **iki backend eklemesi** gerekir (mevcut akışı bozmaz).

### 2.1 `boxes` tablosu
```
boxes:
  id            uuid pk
  mac           varchar unique   -- kutu MAC'i (deviceId ile eşleşir)
  buildingId    uuid fk -> buildings
  name          varchar
  active        boolean default true
  createdAt     timestamptz
```

### 2.2 Aktivasyon endpoint'i
```
POST /calls/box-activate           (auth YOK — MAC kimliktir)
body:  { "deviceId": "<mac>" }
```
Mantık:
1. `boxes` içinde `mac = deviceId` ve `active = true` kaydını bul. Yoksa → `{success:false, message}`.
2. Kutunun `buildingId`'sinden binanın **mevcut QR token**'ını al (ara.html'in kullandığı token).
3. Binadaki daireleri döndür.

Yanıt:
```json
{
  "success": true,
  "buildingId": "3970b681-...",
  "buildingName": "Test Binası",
  "buildingQrToken": "DIAFON-4be2ee99...",
  "apartments": [
    { "apartmentId": "1dead865-...", "flatNo": "1", "name": "Test Sakin" },
    { "apartmentId": "29e501c0-...", "flatNo": "2", "name": "Elis" }
  ]
}
```

> `buildingQrToken` + `apartments`, zaten var olan `GET /buildings/by-qr?token=` mantığının
> aynısıyla üretilir. `call:start-flat` ve `/auth/guest-token` **aynen** kullanılır — yeni
> çağrı mantığı YOK.

### 2.3 Admin paneli — "DiafonBox Ekle"
- Yönetici binayı açar → **DiafonBox Ekle** → kutunun **MAC**'ini girer (+ ad).
- `POST /admin/boxes { mac, buildingId, name }` (yönetici auth) → `boxes` kaydı.
- Kutu boot'ta `box-activate` çağırınca aktive olur.
- İsteğe bağlı: kutu listeleme/silme (`GET/DELETE /admin/boxes`).

---

## 3) Akış özeti
```
Admin: MAC -> bina (boxes)
Kutu boot: box-activate(MAC) -> buildingQrToken + daireler (önbellek)
Ziyaretçi: daire seç -> MobilDiafonBox.call(apartmentId)
   -> guest-token(buildingQrToken) -> socket(guest) -> call:start-flat{apartmentId}
   -> daire sakinlerinin telefonu çalar -> cevap -> WebRTC (kapı kamerası + ses)
Sakin "Kapıyı Aç" -> onRemoteOpenDoor -> BoxHost.openDoor() -> röle
```

## 4) Notlar / TODO
- Kutu UI'si (daire grid/keypad) host'ta; SDK sadece motor + aktivasyon verir.
- İleride: kutunun kendi **QR ekranı** (binaya özel QR → web misafir sayfası).
- `onRemoteOpenDoor` backend sinyaliyle tetiklenmeli — mevcut "kapı aç" olayını
  `MobilDiafonCall.Listener.onRemoteOpenDoor()`e bağla (backend event adına göre).
- SIP birleşik katman (madde 5) geldiğinde: kutu ayrıca Asterisk'e SIP register olabilir
  (marka-bağımsız panellerle aynı hat) — ama Multitek kutusu için SDK yolu daha zengin.

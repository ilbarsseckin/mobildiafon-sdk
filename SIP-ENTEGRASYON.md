# MobilDiafon — İzole SIP Modülü (Flutter)

SIP panelleri (Hikvision / DNAKE / Core / Akuvox) app'e çaldırmak için **izole** bir
SIP-over-WebSocket katmanı. **Mevcut Socket.io/WebRTC akışına dokunmaz.**

## İzolasyon garantileri
- Sadece **iki yeni dosya**: `lib/sip/sip_service.dart`, `lib/sip/sip_incoming_screen.dart`.
- Mevcut `SocketService`, `CallScreen`, QR akışı, `ApiService` **değişmez**.
- SIP register yalnızca sen `SipService.instance.start(...)` çağırınca başlar.
- `SipService.instance.stop()` → SIP tamamen kapanır, eski akış etkilenmez (güvenlik anahtarı).
- SIP çağrısı **kendi ekranını** (`SipIncomingScreen`) açar; mevcut çağrı ekranına karışmaz.

## 1) Bağımlılık (additive)
`pubspec.yaml` → `dependencies:` altına ekle:
```yaml
  sip_ua: ^1.0.0        # en güncel uyumlu sürümü kullan
```
`flutter pub get`. (`flutter_webrtc` zaten projede var — SIP onu kullanır.)

> Not: modül **sip_ua ^1.0.0** için yazıldı. Sürüm farklıysa `answer` / `buildCallOptions`
> imzaları 1-2 satır değişebilir; kod yorumlarında işaretli.

## 2) Dosyaları koy
- `lib/sip/sip_service.dart`
- `lib/sip/sip_incoming_screen.dart`

## 3) Başlat (SIP dünyası binada, login sonrası)
Uygulamanın bir yerinde global `navigatorKey`'in varsa onu kullan (yoksa ekle — additive):

```dart
// main.dart -> MaterialApp(navigatorKey: appNavigatorKey, ...)
final GlobalKey<NavigatorState> appNavigatorKey = GlobalKey<NavigatorState>();
```

SIP olaylarını dinleyen küçük bir sınıf (yeni dosya ya da mevcut bir servis içinde):
```dart
class _SipHandler implements SipEvents {
  @override
  void onSipRegistered(bool ok) => debugPrint('SIP kayıt: $ok');

  @override
  void onSipIncoming(Call call, String caller) {
    appNavigatorKey.currentState?.push(MaterialPageRoute(
      builder: (_) => SipIncomingScreen(call: call, caller: caller),
    ));
  }

  @override
  void onSipEnded(String reason) => debugPrint('SIP bitti: $reason');
}
```

Login/aktivasyon sonrası (bina SIP dünyasıysa):
```dart
SipService.instance.setEvents(_SipHandler());
SipService.instance.start(const SipConfig(
  wssUrl: 'wss://mobildiafon.com:8089/ws',   // IP değil, sertifika ismi!
  domain: 'mobildiafon.com',
  username: 'd10',        // TEST için d10; gerçekte backend her daireye üretir
  password: 'TestD10x',
));
```

Kapatmak (bina SDK dünyasıysa ya da çıkışta):
```dart
SipService.instance.stop();
```

## 4) Test (Core panel → senin app'in)
1. App'i telefonda çalıştır → yukarıdaki `start(...)` ile `d10` olarak register olsun.
2. Sunucuda doğrula:
   ```bash
   docker exec diafon-mobil-asterisk asterisk -rx "pjsip show contacts" | grep -i d10
   ```
   `d10 ... Avail` (Linphone'u kapat, çakışmasın — max_contacts=1).
3. **Core panelinden `10`'u ara** (Contacts → numara `10`).
4. **Senin uygulaman çalar** → Cevapla → kapı kamerası + ses. Kapı Aç = çağrı-içi DTMF.

## 5) Gerçek kullanımda (test sonrası)
- `username/password` sabit `d10` değil, **backend'den daireye özel** gelir (monitör aktivasyonunun SIP karşılığı).
- Register **sadece SIP dünyası binalarda** açılır (backend'den `sipEnabled` bayrağı).
- Arka planda çalma için **FCM push** ile uyandırma eklenecek (dialplan `curl :4000` → backend → push → app register).

## Sunucu tarafı gereksinimler (hazır)
- Asterisk `transport-wss` (8089, Let's Encrypt) — **açık** ✅
- coturn (TURN) — **var** ✅
- Firewall: **TCP 8089** (wss) ve **UDP 3478 + RTP** dışa açık olmalı.
- DNS: `mobildiafon.com` → sunucu IP (sertifika eşleşmesi için).

## Kapı açma (marka farkı)
- Çağrı-içi **DTMF** (`sendDoorDtmf`, ör. `#`) — çoğu panelde çalışır.
- Ya da backend'de **marka HTTP API adapter'ı** (Hikvision ISAPI / DNAKE / Akuvox / Core).

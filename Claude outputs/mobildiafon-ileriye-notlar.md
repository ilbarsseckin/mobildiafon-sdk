# MobilDiafon — İleriye Dönük Not: İnternetsiz Bina Kapısı + İnternetli Ev Monitörü (Köprü)

_Tarih: 2026-09-24 · Durum: ERTELENDİ (ileride yapılacak)_

## Senaryo
- **Bina LAN'ı internetsiz.** Kapı diafonu (IP panel) + ev içi monitörler bu yerel ağda, aralarında **SIP** ile konuşuyor.
- **Ev monitörü çift ağlı:** bir yanda bina LAN'ı (kapıya SIP erişimi), diğer yanda kendi **WiFi'si → internet**.
- Her ev sakini **OTP ile kendi kaydını** ev içi monitörden yapacak.

## Hedeflenen özellikler
1. **Cep telefonu ile uzaktan kapı görüntüsü** (sakin evde değilken).
2. **QR arama** (misafir QR okutup daire arar, çift-kamera).
3. **Bina diafonundan arama** (kapı bir daireyi arar).

## Temel kısıt (fizik/networking)
- Kapı cihazı internetsiz → buluttan **doğrudan** görüntü veremez.
- Bu yüzden bulut özelliklerini **LAN'da internetli bir cihaz köprülemeli** → o cihaz **ev içi monitör** (WiFi'si var).
- Yani **monitör, offline kapının bulut "box"ı (köprü)** rolünü üstlenir:
  - Kapıdan **yerel SIP** ile görüntü/ses alır,
  - kendi **interneti** ile SDK üzerinden buluta **yeniden yayınlar** (WebRTC),
  - buluttan gelen `door:view` / `call:open-door` komutlarını LAN'a çevirir.

## Mimari
```
Kapı diafonu            Ev monitörü (KÖPRÜ)               Bulut                 Uzak
(SIP, internetsiz)  ⇄  LAN'dan SIP alır +           ⇄  mobildiafon.com  ⇄   cep telefonu
                        WiFi ile buluta yayınlar        (WebRTC + Socket)      QR misafir
```

## Yapılacaklar (iş kalemleri)
1. **Çift-ağ yönlendirmesi (Android, monitör):**
   - Kapıya SIP trafiği → **LAN/Ethernet** arayüzünden.
   - Bulut (SDK/OTP) trafiği → **WiFi/internet** arayüzünden.
   - Yöntem: `ConnectivityManager` ile SIP soketini LAN `Network`'üne **bind** et (`network.bindSocket`). `bindProcessToNetwork` kullanma (tüm app'i tek ağa alır).
   - Not: Monitörün LAN IP'si kapının subnet'indeyse, connected-route sayesinde çoğu zaman otomatik doğru yönlenir; sorun olursa bind şart.

2. **SDK'ya "dış video kaynağı" enjekte özelliği (EN AĞIR PARÇA):**
   - Şu an SDK yalnız cihazın **kendi kamerasını** açıyor (`Camera1Enumerator`).
   - Köprü için kapının **SIP/RTSP video akışını** WebRTC'ye beslemek gerek (custom `VideoCapturer` / `VideoSource`'a frame push).
   - **Önce netleştir:** Kapı paneli LAN'da **RTSP/HTTP(MJPEG)** akışı veriyor mu?
     - **Veriyorsa** → RTSP→WebRTC köprüsü **çok daha kolay/temiz.** (Öncelik bu.)
     - **Sadece SIP ise** → Linphone'un çözdüğü kareleri (surface/texture) yakalayıp WebRTC'ye verme — daha zor.

3. **Monitör = box olarak kayıt:**
   - Monitör WiFi/internet ile `box:register` (SDK v1.4.5 foreground service).
   - Bina başına `boxSockets` TEK box tutuyor → köprü box = monitör olacak (kapı değil).

4. **Kapı açma köprüsü:**
   - Buluttan `call:open-door` gelince → monitör kapıya LAN'dan kapı-aç komutu (SIP DTMF / yerel HTTP / röle) gönderir.
   - **Netleştir:** kapı açma tetikleme yöntemi ne?

5. **OTP ile sakin self-kayıt (monitörden):**
   - Monitörde "GSM + OTP" ekranı → bulut backend'e kayıt (monitörün interneti yeter, kapının internetinden bağımsız).

6. **Çağrı yönü (bina diafonundan arama):**
   - Kapı bir daireyi arar → SIP ile monitöre düşer → monitör yerelde gösterir + gerekirse uzaktaki sakine bulut çağrısına köprüler.
   - **Netleştir:** kapı daireyi ararken hedefi nasıl seçiyor (SIP hesapları/numaraları)?

## Karar için bekleyen sorular
- [ ] Kapı paneli **RTSP/HTTP** akışı veriyor mu? (köprü zorluğunu bu belirler)
- [ ] Kapı **açma** komutu nasıl? (DTMF / HTTP / röle)
- [ ] Kapının **arama/hedef** mekanizması (SIP hesap eşlemesi)?
- [ ] Ev içi monitör her zaman kapı ile aynı LAN'da mı?

## Not
- Ev **içindeki** monitörler kapıyı zaten **yerel SIP** ile görür — köprü sadece **uzak telefon + QR** içindir.
- Bu, DiafonBox'ın "köprü" (relay) versiyonu; mevcut analog/IP box mantığından farkı: kamera başka cihazda ve akış SIP/RTSP'ten geliyor.

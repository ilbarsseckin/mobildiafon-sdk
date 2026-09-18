# JitPack ile Yayınlama ve Kullanım

Bu repo JitPack'e hazırdır: bir Android kütüphane modülü (`:mobildiafonsdk`) + Gradle
wrapper (8.7) + `maven-publish` + `jitpack.yml`. Sen (SDK sağlayıcı) GitHub'a push'lar
ve tag atarsın; Multitek tek satır `implementation` ile çeker.

---

## A) SDK SAĞLAYICI (sen) — yayınlama

### 1. GitHub reposu
Bu klasörü (`mobildiafon-sdk/`) bir GitHub reposunun **kökü** yap:
```bash
cd mobildiafon-sdk
git init
git add .
git commit -m "MobilDiafon SDK v1.0.0"
git branch -M main
git remote add origin https://github.com/<kullanıcı>/mobildiafon-sdk.git
git push -u origin main
```
> `reference-host/` de repoda durur (entegrasyon örneği olarak); modül olmadığı için
> derlemeye girmez, JitPack sadece `:mobildiafonsdk`'i derler.

### 2. Sürüm etiketi (JitPack sürümü tag'den okur)
```bash
git tag v1.0.0
git push origin v1.0.0
```

### 3. JitPack'te tetikle
`https://jitpack.io/#<kullanıcı>/mobildiafon-sdk` adresine gir → `v1.0.0` satırında
**Get it**'e bas. İlk derleme birkaç dk sürer; log yeşil olunca yayında.
(Repo herkese açıksa hesap bağlamana bile gerek yok.)

### 4. Yeni sürüm
Kod değişti → commit → yeni tag (`v1.0.1`) → push. Multitek sadece sürüm numarasını yükseltir.

---

## B) MÜŞTERİ (Multitek) — kullanım

### 1. JitPack deposunu ekle
`settings.gradle` (veya kök `build.gradle`, projelerine göre):
```gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }   // <-- ekle
    }
}
```

### 2. Bağımlılığı ekle — `.aar` DOSYASI YOK, sadece koordinat
`app/build.gradle`:
```gradle
dependencies {
    implementation 'com.github.<kullanıcı>:mobildiafon-sdk:v1.0.0'
}
```
WebRTC ve socket.io **transitive** olarak otomatik iner (kütüphanede `api` ile tanımlı).

### 3. Entegrasyon kiti
`reference-host/` içindeki 3 java + 2 layout'u kendi uygulamalarına kopyalayıp
kendi `I2CUtil` / `R` / `CameraPreview`'lerine bağlarlar (SDK-ENTEGRASYON.md bölüm 3–7).
`DiafonHost` implementasyonu **onlarda** olmak zorunda; SDK donanıma dokunmaz.

---

## Notlar / sorun giderme

- **minSdk 23**, compileSdk 34, JDK 17. Multitek projesi daha eski AGP kullanıyorsa
  `.aar`'ı JitPack yine üretir; onların sadece `implementation` satırı + jitpack repo'su yeterli.
- JitPack derlemesi kırılırsa log: `https://jitpack.io/com/github/<kullanıcı>/mobildiafon-sdk/v1.0.0/build.log`
- Özel/kapalı repo ise Multitek'in JitPack'e bir **auth token** tanımlaması gerekir
  (JitPack → hesap → private repo). Açık repoda gerek yok.
- Kurumsal tercih ederlerse aynı modül **Maven Central** veya onların **Nexus/Artifactory**'sine
  de publish edilebilir; `implementation` satırı mantığı aynı kalır.

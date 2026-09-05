# LinkDroid Android Remote

LinkDroid adalah aplikasi Android native yang seluruh UI dan kemampuan perangkatnya
dibuat dengan Kotlin dan Jetpack Compose. Backend Node.js untuk API dan signaling
berada di folder `backend/`; repository ini tidak memiliki website atau Expo
workspace.

## Build APK

Dari folder proyek Android:

```bash
cd artifacts/linkdroid-android/native-kotlin
gradle assembleDebug
```

APK debug dibuat di:
`artifacts/linkdroid-android/native-kotlin/app/build/outputs/apk/debug/app-debug.apk`

Buka folder `artifacts/linkdroid-android/native-kotlin` langsung di Android Studio
untuk menjalankan aplikasi pada emulator atau perangkat fisik.

Tombol Run Replit menjalankan perintah build Gradle yang sama. Build membutuhkan
Java dan Android SDK dengan platform Android 35 serta build-tools yang sesuai;
SDK tidak disimpan di repository dan `local.properties` tetap diabaikan oleh
Git. Jika SDK belum tersedia, workflow akan gagal dengan pesan lokasi SDK dan
perlu dijalankan pada environment Android/Android Studio yang sudah memiliki
platform tersebut.

## Konfigurasi backend

Domain TLS yang disiapkan untuk integrasi backend adalah:
`https://103-245-38-142.sslip.io`

Backend Node.js, skema Prisma, API auth/device/task/session, dan relay WebSocket
signaling tersedia di `backend/`. Backend harus listen di `127.0.0.1:3000` agar
sesuai dengan Nginx. Integrasi Android dengan token, WebSocket signaling, dan
WebRTC video/data channel sudah tersedia di source. Koneksi dua perangkat, TURN,
dan pemulihan setelah gangguan jaringan tetap perlu diuji sebelum tombol
`Hubungkan` dianggap siap untuk penggunaan production.

## Alur monitoring petugas

- Saat login, pengguna memilih peran `Admin` atau `Petugas`.
- Admin memasukkan ID device petugas dari layar Monitoring untuk memulai
  permintaan sesi remote.
- Petugas melihat ID device-nya di beranda, lalu mengisi nama pelanggan, IDPEL,
  alamat, wilayah, dan provinsi pada formulir Data pelanggan.
- Tombol `Simpan & Lanjut` memvalidasi IDPEL 11–12 digit dan seluruh field sebelum
  membawa petugas ke tahap PLN Mobile.
- Di tahap PLN Mobile, petugas dapat meminta izin MediaProjection Android agar
  layar dapat dipantau. Pemantauan antar-device yang benar-benar tersambung
  memerlukan APK yang sudah memakai backend signaling/WebRTC.

## Stack

- Kotlin
- Jetpack Compose Material 3
- Android Gradle Plugin
- MediaProjection dan foreground service
- Accessibility Service untuk aksi sentuhan yang disetujui pengguna
- SharedPreferences untuk sesi lokal dan daftar perangkat
- Node.js, Express, Prisma, PostgreSQL, JWT, dan WebSocket untuk backend

## Struktur

- `artifacts/linkdroid-android/native-kotlin/app/src/main/java/app/linkdroid/remote/MainActivity.kt` — UI native login, beranda, perangkat, sesi, dan pengaturan.
- `artifacts/linkdroid-android/native-kotlin/app/src/main/java/app/linkdroid/remote/ScreenCaptureService.kt` — foreground service dan VirtualDisplay.
- `artifacts/linkdroid-android/native-kotlin/app/src/main/java/app/linkdroid/remote/RemoteAccessibilityService.kt` — service kontrol sentuhan.
- `artifacts/linkdroid-android/native-kotlin/app/src/main/java/app/linkdroid/remote/RemoteSessionCoordinator.kt` — state machine sesi.
- `artifacts/linkdroid-android/native-kotlin/app/src/main/java/app/linkdroid/remote/RemoteCommand.kt` — schema command tap, swipe, text, back, dan home.
- `artifacts/linkdroid-android/native-kotlin/app/src/main/AndroidManifest.xml` — activity, service, dan permission Android.

## Command remote dan pemulihan sesi

Setelah session disetujui, controller mengirim command aksesibilitas melalui
WebRTC data channel jika tersedia, dengan fallback ke WebSocket signaling
terautentikasi. Backend membatasi command signaling ke device receiver pada
session `APPROVED` atau `ACTIVE`, memvalidasi payload, dan mengembalikan hasil
atau timeout. Receiver harus mengaktifkan Accessibility Service Android agar
gesture/global action dapat dijalankan.

Metadata session ID, device pasangan, role, dan draft data pelanggan disimpan di
preferences sehingga Activity dapat memulihkan state setelah recreation atau
process death. Capture service dan koneksi media WebRTC tetap perlu dipulihkan
secara eksplisit setelah proses benar-benar mati.

## Prinsip keamanan

- Screen capture hanya aktif setelah dialog persetujuan MediaProjection Android.
- Accessibility Service tidak diaktifkan diam-diam.
- Sesi harus dapat dihentikan dari aplikasi.
- Transport signaling/WebRTC terautentikasi masih diperlukan untuk koneksi antarperangkat nyata.

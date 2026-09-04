# LinkDroid Android Remote

LinkDroid adalah aplikasi Android native yang seluruh UI dan kemampuan perangkatnya
dibuat dengan Kotlin dan Jetpack Compose. Repository ini sengaja tidak lagi memiliki
website, Expo, server Node.js, atau workspace JavaScript.

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
Android SDK dengan platform Android 35 dan build-tools yang sesuai; SDK tidak
disimpan di repository dan `local.properties` tetap diabaikan oleh Git.

## Stack

- Kotlin
- Jetpack Compose Material 3
- Android Gradle Plugin
- MediaProjection dan foreground service
- Accessibility Service untuk aksi sentuhan yang disetujui pengguna
- SharedPreferences untuk sesi lokal dan daftar perangkat

## Struktur

- `artifacts/linkdroid-android/native-kotlin/app/src/main/java/app/linkdroid/remote/MainActivity.kt` — UI native login, beranda, perangkat, sesi, dan pengaturan.
- `artifacts/linkdroid-android/native-kotlin/app/src/main/java/app/linkdroid/remote/ScreenCaptureService.kt` — foreground service dan VirtualDisplay.
- `artifacts/linkdroid-android/native-kotlin/app/src/main/java/app/linkdroid/remote/RemoteAccessibilityService.kt` — service kontrol sentuhan.
- `artifacts/linkdroid-android/native-kotlin/app/src/main/java/app/linkdroid/remote/RemoteSessionCoordinator.kt` — state machine sesi.
- `artifacts/linkdroid-android/native-kotlin/app/src/main/AndroidManifest.xml` — activity, service, dan permission Android.

## Prinsip keamanan

- Screen capture hanya aktif setelah dialog persetujuan MediaProjection Android.
- Accessibility Service tidak diaktifkan diam-diam.
- Sesi harus dapat dihentikan dari aplikasi.
- Transport signaling/WebRTC terautentikasi masih diperlukan untuk koneksi antarperangkat nyata.

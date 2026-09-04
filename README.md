# LinkDroid Android Monorepo

Repository ini adalah satu repository untuk aplikasi Android native berbasis
Kotlin dan Jetpack Compose. Tidak ada workspace web atau backend palsu yang
disamarkan sebagai fitur remote.

Kemampuan Android seperti MediaProjection dan
Accessibility Service diimplementasikan langsung melalui Kotlin dan Android
SDK.

## Struktur repository

- `artifacts/linkdroid-android/native-kotlin/` — satu-satunya project Gradle
  Android yang menjadi source of truth aplikasi.
- `SYSTEM_GAPS.md` — status fitur, blocker produksi, dan checklist launch.
- `replit.md` — instruksi build dan batasan environment.

Server signaling belum disertakan karena kontrak API, kredensial database, dan
deployment server belum tersedia. Domain produksi yang disiapkan untuk
integrasi adalah `https://103-245-38-142.sslip.io`; aplikasi belum mengirim
request ke endpoint tersebut sampai backend memiliki kontrak yang tervalidasi.

## Build APK debug

```bash
cd artifacts/linkdroid-android/native-kotlin
gradle assembleDebug
```

APK hasil build tersedia di:

```text
artifacts/linkdroid-android/native-kotlin/app/build/outputs/apk/debug/app-debug.apk
```

Buka folder `artifacts/linkdroid-android/native-kotlin` di Android Studio untuk
menjalankan aplikasi pada emulator atau perangkat Android.
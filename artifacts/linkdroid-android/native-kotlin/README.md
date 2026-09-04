# LinkDroid native Android app

Folder ini adalah aplikasi Android native berbasis Kotlin dan Jetpack Compose.
Repository ini tidak menyediakan website, Expo, atau server JavaScript. Output
aplikasi berasal langsung dari proyek Gradle ini.

## Build APK

Dari folder `artifacts/linkdroid-android/native-kotlin` jalankan:

```bash
gradle assembleDebug
```

APK debug akan berada di:
`app/build/outputs/apk/debug/app-debug.apk`

Komponen native yang digunakan:

- `MediaProjection` melalui foreground service untuk berbagi layar.
- `AccessibilityService` untuk menerima aksi sentuhan dari perangkat pengendali.
- `RemoteSessionCoordinator` sebagai state machine sesi.
- Manifest permissions yang diperlukan Android modern.
- UI login, beranda, daftar perangkat, sesi remote, dan pengaturan dalam Kotlin
  Compose.

## Jalur produksi

1. Tambahkan transport signaling/WebRTC yang memiliki autentikasi per-sesi.
2. Minta persetujuan `MediaProjection` dan Accessibility secara eksplisit dari pengguna.
3. Kirim frame layar melalui koneksi terenkripsi dan validasi setiap input remote.
4. Tambahkan audit log, timeout sesi, tombol putus darurat, dan pembatasan perangkat.

Jangan menyalakan Accessibility Service secara diam-diam. Kontrol perangkat hanya
boleh aktif setelah pengguna di perangkat penerima menyetujui sesi.
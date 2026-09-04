# LinkDroid native Android foundation

Folder ini berisi fondasi native Android berbasis Kotlin untuk build produksi.
Preview interaktif di folder utama dipakai untuk memvalidasi alur dan tampilan
tanpa membutuhkan Android SDK di lingkungan pengembangan ini.

Komponen native yang sudah disiapkan:

- `MediaProjection` melalui foreground service untuk berbagi layar.
- `AccessibilityService` untuk menerima aksi sentuhan dari perangkat pengendali.
- `RemoteSessionCoordinator` sebagai state machine sesi.
- Manifest permissions yang diperlukan Android modern.

## Jalur produksi

1. Tambahkan transport signaling/WebRTC yang memiliki autentikasi per-sesi.
2. Minta persetujuan `MediaProjection` dan Accessibility secara eksplisit dari pengguna.
3. Kirim frame layar melalui koneksi terenkripsi dan validasi setiap input remote.
4. Tambahkan audit log, timeout sesi, tombol putus darurat, dan pembatasan perangkat.

Jangan menyalakan Accessibility Service secara diam-diam. Kontrol perangkat hanya
boleh aktif setelah pengguna di perangkat penerima menyetujui sesi.
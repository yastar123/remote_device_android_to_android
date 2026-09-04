# Kekurangan Sistem LinkDroid

Dokumen ini mencatat bagian yang masih kurang dari sistem LinkDroid Android
native berbasis Kotlin. Dokumen ini menjadi checklist sebelum aplikasi
digunakan untuk dukungan remote antarperangkat secara nyata.

## Ringkasan kondisi saat ini

Fondasi aplikasi Android sudah tersedia:

- Aplikasi native Kotlin dengan Jetpack Compose.
- Login lokal/demo.
- Navigasi Beranda, Perangkat, Sesi Remote, dan Pengaturan.
- Penyimpanan daftar perangkat secara lokal menggunakan `SharedPreferences`.
- Permintaan izin MediaProjection.
- Foreground service untuk screen capture.
- Accessibility Service untuk menjalankan gesture.
- Pengaturan notifikasi dan status Accessibility Service.

Namun, aplikasi belum menjadi sistem remote-support end-to-end. Sebagian alur
masih berjalan sebagai state lokal di satu perangkat dan belum berkomunikasi
dengan perangkat Android lain.

## Prioritas kritis

### 1. Signaling antarperangkat belum tersedia

Belum ada server atau kanal signaling untuk:

- Mendaftarkan device ID ke server.
- Menemukan perangkat tujuan.
- Mengirim permintaan sesi.
- Menerima atau menolak permintaan sesi.
- Menyinkronkan status sesi.
- Menangani reconnect dan sesi yang kedaluwarsa.

Akibatnya, tombol `Hubungkan` hanya mengubah tampilan dan state lokal. Belum ada
permintaan yang benar-benar sampai ke perangkat lain.

### 2. Transport WebRTC belum tersedia

Belum ada implementasi WebRTC atau transport real-time lain untuk mengirim:

- Frame layar dari `MediaProjection`.
- Audio sesi.
- Data channel untuk perintah remote.
- Status koneksi, latency, dan kualitas jaringan.

`ScreenCaptureService` saat ini membuat VirtualDisplay dan membaca frame agar
buffer tidak penuh, tetapi frame tersebut belum dikodekan dan dikirim ke
perangkat lain.

### 3. Alur penerima sesi belum tersedia

Belum ada layar atau state khusus untuk perangkat penerima agar pengguna dapat:

- Melihat permintaan sesi masuk.
- Memverifikasi identitas pengendali.
- Menyetujui atau menolak akses.
- Menghentikan sesi dari sisi penerima.
- Melihat indikator bahwa layar dan kontrol sedang dibagikan.

Persetujuan pengguna harus menjadi syarat sebelum screen sharing atau kontrol
remote aktif.

### 4. Kontrol remote belum memiliki jalur perintah

`RemoteAccessibilityService` sudah memiliki fungsi gesture tap, tetapi belum ada
komponen yang menerima perintah tap, swipe, atau input lain dari koneksi remote.
Belum tersedia:

- Validasi koordinat dan tipe perintah.
- Antrian perintah.
- Timeout perintah.
- Konfirmasi keberhasilan atau kegagalan gesture.
- Pembatasan perintah hanya untuk sesi yang telah disetujui.

## Fitur aplikasi yang masih terbatas

### 5. Login belum menggunakan autentikasi nyata

Login saat ini hanya disimpan secara lokal. Password tidak diverifikasi ke
server dan belum ada:

- Registrasi akun.
- Session token yang aman.
- Refresh token.
- Logout dari semua perangkat.
- Reset password.
- Verifikasi email.
- Manajemen akun pengguna.

Sistem produksi membutuhkan provider autentikasi atau backend yang aman.

### 6. Device ID masih hardcoded/demo

ID perangkat utama dan daftar perangkat awal masih berupa data contoh di kode.
Belum ada:

- Device ID unik yang dibuat aman saat instalasi.
- Registrasi device ke akun.
- Rotasi atau pencabutan device.
- Verifikasi kepemilikan perangkat.
- Status online yang berasal dari heartbeat nyata.

### 7. Data perangkat belum menggunakan penyimpanan yang kuat

Daftar perangkat disimpan dalam `SharedPreferences` menggunakan string yang
dipisahkan karakter tertentu. Pendekatan ini belum ideal untuk:

- Banyak perangkat.
- Perubahan struktur data.
- Sinkronisasi antarperangkat.
- Migrasi data.
- Enkripsi data sensitif.

Untuk data yang lebih kompleks, gunakan database lokal seperti Room dan
sinkronisasi terautentikasi dengan backend.

### 8. Audio belum diimplementasikan

Toggle `Audio sesi` belum menangkap, mengirim, atau memutar audio. Fitur ini
masih berupa state UI lokal dan baru dapat berfungsi setelah permission audio,
pipeline capture, encoding, transport, dan playback tersedia.

### 9. Notifikasi belum mencakup seluruh lifecycle sesi

Notifikasi dasar untuk sesi lokal sudah tersedia, tetapi belum ada:

- Notifikasi permintaan sesi masuk.
- Notifikasi sesi disetujui atau ditolak.
- Notifikasi koneksi terputus.
- Deep link untuk membuka sesi tertentu.
- Sinkronisasi notifikasi saat aplikasi berada di background.

## Keamanan dan privasi

### 10. Otorisasi sesi belum tersedia

Belum ada access token atau session capability yang membatasi siapa yang boleh
mengakses perangkat. Device ID saja tidak boleh menjadi kredensial akses.

Sistem produksi membutuhkan:

- Token sesi berumur pendek.
- Persetujuan eksplisit penerima.
- Validasi identitas kedua pihak.
- Pembatasan satu sesi aktif jika diperlukan.
- Pencabutan akses.
- Proteksi replay attack.

### 11. Enkripsi end-to-end belum dirancang

Manifest memiliki permission internet, tetapi belum ada transport jaringan.
Saat transport ditambahkan, signaling dan media harus menggunakan koneksi
terenkripsi serta validasi identitas peer.

### 12. Audit log dan riwayat sesi belum tersedia

Belum ada pencatatan:

- Siapa yang memulai sesi.
- Perangkat mana yang diakses.
- Waktu persetujuan dan penghentian.
- Permission yang digunakan.
- Perintah remote yang dijalankan.
- Alasan kegagalan sesi.

Audit log diperlukan untuk investigasi keamanan dan dukungan pengguna.

### 13. Pengelolaan permission belum lengkap

Permission dasar sudah diminta, tetapi belum semua kondisi ditangani dengan
jelas, seperti:

- Pengguna menolak notifikasi secara permanen.
- Pengguna mencabut Accessibility Service saat sesi berlangsung.
- Pengguna mencabut izin screen capture.
- Aplikasi masuk background atau dihentikan sistem.
- Perangkat menjalankan pembatasan baterai.
- Perubahan orientasi atau ukuran layar.

Setiap kondisi perlu menghasilkan status dan pesan yang jelas, serta
membersihkan sesi dengan aman.

## Stabilitas dan kualitas

### 14. Belum ada test otomatis yang memadai

Belum tersedia coverage untuk:

- Validasi dan format device ID.
- Penyimpanan dan pemuatan daftar perangkat.
- Penolakan device duplikat.
- Lifecycle `RemoteSessionCoordinator`.
- State login, logout, dan sesi.
- Penanganan permission.
- Lifecycle `ScreenCaptureService`.
- Kontrak signaling dan perintah gesture.

Tambahkan unit test Kotlin, instrumented test Android, dan pengujian manual
pada emulator serta perangkat fisik.

### 15. Error handling jaringan belum ada

Karena transport belum tersedia, aplikasi juga belum menangani:

- Tidak ada koneksi internet.
- Server signaling tidak tersedia.
- Peer timeout.
- ICE/WebRTC failure.
- Reconnect.
- Sesi yang ditolak.
- Perangkat tujuan offline.
- Sesi yang sudah kedaluwarsa.

Semua kegagalan harus terlihat oleh pengguna dan tidak meninggalkan service
atau state sesi yang menggantung.

### 16. Belum ada lifecycle state lintas proses

State sesi dan status screen sharing terutama berada di state Activity. Jika
Activity dibuat ulang, proses dimatikan sistem, atau service berjalan terpisah,
state UI dapat tidak sepenuhnya mencerminkan state service.

Diperlukan state repository atau komunikasi service-to-Activity yang tahan
terhadap recreation dan process lifecycle.

### 17. Belum ada pipeline CI/CD Android

Belum ada pemeriksaan otomatis untuk:

- Build debug dan release.
- Kotlin lint atau Android lint.
- Unit test.
- Signing APK/AAB.
- Verifikasi dependency.
- Penyimpanan artifact build.

Build lokal saat ini juga membutuhkan Android SDK platform 35 dan build-tools
yang sesuai di environment.

## Operasional dan kesiapan produksi

### 18. Belum ada backend operasional

Sistem produksi membutuhkan deployment untuk signaling/API, termasuk:

- Domain dan TLS.
- Database pengguna dan perangkat.
- Penyimpanan konfigurasi.
- Rate limiting.
- Monitoring dan logging.
- Health check.
- Backup dan pemulihan.
- Mekanisme upgrade schema.

### 19. Belum ada konfigurasi environment yang lengkap

Belum ada definisi environment untuk endpoint backend, konfigurasi WebRTC,
logging, dan mode development/production. Nilai rahasia tidak boleh ditulis
langsung ke source code atau di-commit ke repository.

### 20. Belum ada kebijakan privasi dan persetujuan pengguna

Karena aplikasi dapat membagikan layar dan menjalankan kontrol sentuhan,
dibutuhkan:

- Penjelasan penggunaan screen capture.
- Penjelasan Accessibility Service.
- Kebijakan privasi.
- Persetujuan akses remote.
- Informasi durasi dan penghentian sesi.
- Mekanisme pelaporan penyalahgunaan.

### 21. Belum ada dokumentasi penggunaan end-to-end

Dokumentasi yang belum tersedia meliputi:

- Cara memasang APK.
- Cara mendaftarkan perangkat.
- Cara mengaktifkan Accessibility Service.
- Cara memulai sesi sebagai pengendali.
- Cara menyetujui sesi sebagai penerima.
- Cara menghentikan sesi darurat.
- Troubleshooting permission dan jaringan.

## Urutan pekerjaan yang disarankan

1. Tentukan model akun, device ID, dan otorisasi sesi.
2. Bangun backend signaling dengan autentikasi dan TLS.
3. Implementasikan alur permintaan, persetujuan, penolakan, dan penghentian
   sesi.
4. Tambahkan WebRTC untuk video layar dan data channel.
5. Hubungkan perintah remote ke Accessibility Service dengan validasi ketat.
6. Tambahkan audio jika memang diperlukan oleh produk.
7. Perkuat penyimpanan, lifecycle, audit log, dan error handling.
8. Tambahkan unit test, instrumented test, lint, dan pipeline build.
9. Lengkapi kebijakan privasi serta dokumentasi penggunaan.
10. Uji pada beberapa versi Android dan perangkat fisik sebelum rilis.

## Kesimpulan

Repository ini sudah memiliki fondasi UI dan service Android native Kotlin,
tetapi belum memenuhi kebutuhan sistem remote-support produksi. Kekurangan
terbesar adalah belum adanya autentikasi nyata, signaling antarperangkat,
transport WebRTC, alur persetujuan penerima, dan pengujian end-to-end.
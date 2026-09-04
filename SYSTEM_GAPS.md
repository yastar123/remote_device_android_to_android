# Status Kesiapan Launch LinkDroid

Dokumen ini mencatat fitur yang sudah tersedia, hasil verifikasi environment,
dan bagian yang masih kurang dari sistem LinkDroid Android native berbasis
Kotlin. Dokumen ini menjadi checklist sebelum aplikasi digunakan untuk
dukungan remote antarperangkat secara nyata.

**Pembaruan terakhir:** 4 September 2026
**Struktur repository:** satu repository dengan satu project Gradle Android
sebagai source of truth di `artifacts/linkdroid-android/native-kotlin/`
**Endpoint produksi yang disiapkan:** `https://103-245-38-142.sslip.io`

> Status penting: endpoint HTTPS tersebut merespons `502 Bad Gateway` saat
> diverifikasi dari environment Replit. HTTP merespons `301` ke HTTPS. Karena
> itu domain belum dapat dinyatakan siap untuk backend produksi.

## Ringkasan kondisi saat ini

Fondasi aplikasi Android yang sudah tersedia:

- Aplikasi native Kotlin dengan Jetpack Compose.
- Login lokal/demo dengan validasi email dan panjang password.
- Navigasi Beranda, Perangkat, Sesi Remote, dan Pengaturan.
- Penyimpanan daftar perangkat secara lokal menggunakan `SharedPreferences`,
  termasuk penambahan, pencegahan ID duplikat, koneksi dari daftar, dan hapus.
- ID perangkat lokal dibuat sekali saat instalasi dan ditampilkan saat berbagi,
  bukan lagi ID perangkat yang ditulis tetap di alur utama.
- Permintaan izin MediaProjection.
- Foreground service untuk screen capture.
- Accessibility Service untuk menjalankan gesture.
- Pengaturan notifikasi dan status Accessibility Service.
- Penghentian sesi, penghentian screen sharing, notifikasi sesi, dan logout
  yang membersihkan state sesi lokal.
- Pendaftaran device otomatis dari APK sudah diimplementasikan sebagai client:
  setelah login, APK mencoba mengirim data device ke endpoint backend dan
  menyediakan tombol pendaftaran ulang.
- Perintah Run Replit untuk menjalankan `gradle assembleDebug`; belum ada
  workflow server atau workflow Android yang berhasil berjalan.

Namun, aplikasi belum menjadi sistem remote-support end-to-end. Sebagian alur
masih berjalan sebagai state lokal di satu perangkat dan belum berkomunikasi
dengan perangkat Android lain.

## Matriks kesiapan launch

| Area | Status | Keterangan |
| --- | --- | --- |
| Repository | Siap secara struktur | Satu repo dengan satu project Gradle Android sebagai source aplikasi; folder metadata artefak import bukan source backend atau web. |
| Konfigurasi build | Tersedia | `.replit` menjalankan `gradle assembleDebug` dan Java/Gradle tersedia. |
| Build APK | Gagal di environment ini | Build berhenti sebelum kompilasi karena `ANDROID_HOME`/`ANDROID_SDK_ROOT` tidak ada dan Android SDK platform 35 tidak tersedia. |
| UI/menu | Terimplementasi, belum diuji runtime | Kode memiliki Login, Beranda, Perangkat, Sesi Remote, dan Pengaturan, tetapi belum ada APK/emulator untuk membuktikan alurnya berjalan. |
| Login produksi | Belum siap | Masih validasi dan penyimpanan lokal/demo. |
| Device registration | Client tersedia, server belum siap | APK mencoba registrasi otomatis, tetapi endpoint backend belum dapat dihubungi dan belum ada autentikasi/pairing server. |
| Screen capture lokal | Terimplementasi, belum diuji perangkat | MediaProjection dan foreground service sudah dibuat; belum ada pengiriman frame dan belum diverifikasi di perangkat fisik. |
| Remote control | Belum siap | Accessibility Service tersedia, tetapi belum menerima command dari peer. |
| Signaling | Belum tersedia | Belum ada backend/WebSocket. |
| WebRTC/video | Belum tersedia | Frame belum di-encode atau dikirim. |
| Audio | Belum tersedia | Toggle ditampilkan nonaktif agar tidak mengklaim fitur yang belum ada. |
| TLS/domain | Belum siap | Endpoint yang diberikan mengembalikan 502 saat pengecekan. |
| Release signing | Belum tersedia | Belum ada keystore dan pipeline AAB/release. |
| QA launch | Belum siap | Belum ada unit/instrumented test, APK yang berhasil dibuat di environment ini, atau uji perangkat fisik. |

### Alur yang sudah ditulis di kode, tetapi belum terverifikasi runtime

Kode dimaksudkan untuk mendukung alur berikut setelah APK berhasil dibuat dan
dijalankan pada emulator/perangkat:

1. Masuk menggunakan email valid dan password minimal 6 karakter, atau tombol
   `Coba demo`.
2. Membuka setiap menu bawah: `Beranda`, `Perangkat`, dan `Pengaturan`.
3. Menambahkan perangkat dengan nama dan ID 9 digit, mencegah duplikat,
   memulai sesi lokal dari perangkat online, dan menghapus perangkat tersimpan.
4. Membuat ID perangkat lokal yang persisten dan membagikannya melalui Android
   share sheet.
5. Meminta izin MediaProjection, menyalakan/mematikan foreground capture, dan
   menghentikannya dari menu Sesi Remote atau Pengaturan.
6. Membuka pengaturan Accessibility Service Android dan membaca statusnya saat
   kembali ke aplikasi.
7. Mengaktifkan/mematikan notifikasi sesi, mengakhiri sesi, dan logout.

Daftar di atas adalah kemampuan yang terlihat dari source code, bukan hasil
pengujian berhasil pada perangkat. Pengujian di atas juga belum berarti remote
support antarperangkat sudah bekerja:
alur koneksi saat ini hanya mengubah state lokal sampai backend dan transport
real-time tersedia.

### Bukti verifikasi environment terakhir

- `gradle help` berhasil.
- `gradle assembleDebug` gagal sebelum kompilasi dengan pesan `SDK location not
  found`.
- `ANDROID_HOME` dan `ANDROID_SDK_ROOT` tidak ter-set; `sdkmanager` tidak
  tersedia.
- Tidak ada emulator atau perangkat Android yang terhubung untuk pengujian UI,
  permission, service, dan menu.
- `https://103-245-38-142.sslip.io/` merespons HTTP `502`.
- `http://103-245-38-142.sslip.io/` merespons HTTP `301` menuju HTTPS.

### Kontrak registrasi device yang dipakai APK

Client APK sekarang mengirim:

`POST https://103-245-38-142.sslip.io/api/v1/devices/register`

Dengan JSON:

```json
{
  "email": "akun pengguna",
  "deviceId": "ID lokal 9 digit",
  "deviceName": "manufacturer dan model Android",
  "androidVersion": "versi Android",
  "appVersion": "versi aplikasi"
}
```

Respons HTTP `2xx` dianggap berhasil dan statusnya ditampilkan sebagai
`Device sudah terdaftar di server`. Respons non-`2xx`, termasuk `502`, dianggap
gagal dan ditampilkan di menu Pengaturan sehingga pengguna dapat mencoba
pendaftaran ulang. Endpoint dan skema ini belum tersedia/terverifikasi di
server, dan request belum memiliki access token karena login APK masih lokal.

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

### 6. Device ID belum terdaftar ke server

ID perangkat utama sekarang dibuat lokal sekali dan disimpan di preferences,
t tetapi belum memiliki identitas server yang terverifikasi. APK sekarang
mencoba mendaftarkan device ke endpoint pada bagian kontrak di atas, namun
server belum merespons sukses. Daftar perangkat awal tetap berupa data demo.
Belum ada:

- Device ID server-side atau pairing yang terautentikasi.
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

Toggle `Audio sesi` sengaja dinonaktifkan dan diberi keterangan belum tersedia.
Fitur ini baru dapat berfungsi setelah permission audio, pipeline capture,
encoding, transport, dan playback tersedia.

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

Sistem produksi membutuhkan deployment untuk signaling/API pada domain yang
diberikan, termasuk:

- Domain dan TLS yang sehat. Saat pengecekan 4 September 2026,
  `https://103-245-38-142.sslip.io` mengembalikan `502 Bad Gateway`, sehingga
  upstream backend atau reverse proxy masih perlu diperbaiki.
- Database pengguna dan perangkat.
- Penyimpanan konfigurasi.
- Rate limiting.
- Monitoring dan logging.
- Health check.
- Backup dan pemulihan.
- Mekanisme upgrade schema.

### 19. Konfigurasi endpoint sudah disiapkan, tetapi kontrak belum terhubung

Endpoint domain produksi sudah dicatat sebagai `BuildConfig` untuk menjadi
tujuan integrasi berikutnya. Belum ada client API, kontrak endpoint,
konfigurasi WebRTC, logging, atau mode development/production yang benar-benar
terhubung. Nilai rahasia tidak boleh ditulis langsung ke source code atau
di-commit ke repository.

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

## Checklist sebelum menyatakan siap launch

- [ ] Backend auth dan signaling aktif di domain TLS, bukan hanya reverse proxy.
- [ ] Health check domain mengembalikan status sukses dan sertifikat valid.
- [ ] Kontrak API/WebSocket disepakati dan diimplementasikan di Android.
- [ ] Login, refresh token, pairing device, dan pencabutan akses diuji.
- [ ] Alur request, approve, reject, end session diuji pada dua perangkat.
- [ ] WebRTC video/data channel tersambung melalui STUN/TURN yang tervalidasi.
- [ ] Command remote divalidasi, dibatasi sesi yang disetujui, dan memiliki
  timeout/error response.
- [ ] MediaProjection, Accessibility, notifikasi, background, dan battery
  restriction diuji pada versi Android target.
- [ ] Unit test, instrumented test, lint, dan build release berhasil.
- [ ] Keystore/AAB, privacy policy, consent, abuse reporting, monitoring,
  backup, dan rollback operasional tersedia.

## Kesimpulan

Repository ini sudah memiliki fondasi UI dan service Android native Kotlin
dalam satu project Gradle yang dapat dijadikan basis build APK. Menu dan
alur lokal utama sudah tersedia, tetapi aplikasi belum memenuhi kebutuhan
sistem remote-support produksi. Kekurangan terbesar adalah backend domain yang
masih 502, autentikasi nyata, signaling antarperangkat, transport WebRTC,
alur persetujuan penerima, kontrol remote dari jaringan, dan pengujian
end-to-end. Aplikasi belum boleh dipasarkan sebagai remote-support aktif
sampai checklist di atas selesai.
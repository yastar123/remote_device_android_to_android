# Status Kesiapan Launch LinkDroid

Dokumen ini mencatat fitur yang sudah tersedia, hasil verifikasi environment,
dan bagian yang masih kurang dari sistem LinkDroid Android native berbasis
Kotlin. Dokumen ini menjadi checklist sebelum aplikasi digunakan untuk
dukungan remote antarperangkat secara nyata.

**Pembaruan terakhir:** 5 September 2026
**Struktur repository:** satu repository dengan satu project Gradle Android
sebagai source of truth di `artifacts/linkdroid-android/native-kotlin/`
**Endpoint produksi yang disiapkan:** `https://103-245-38-142.sslip.io`

> Status penting: source backend LinkDroid sekarang tersedia di repository,
> tetapi endpoint HTTPS masih merespons `502 Bad Gateway` karena backend belum
> dideploy dan belum listen di `127.0.0.1:3000` pada VPS. HTTP merespons `301`
> ke HTTPS. Jadi lapisan proxy siap, deployment aplikasi belum dilakukan.

## Infrastruktur VPS yang sudah tersedia

Data berikut berasal dari snapshot konfigurasi VPS per 5 September 2026:

### Server dan reverse proxy

- VPS menggunakan Rocky Linux 8.
- Server bersifat shared/multi-tenant. Aplikasi lain di server yang sama tidak
  boleh terganggu.
- Domain produksi: `103-245-38-142.sslip.io`.
- Nginx memiliki konfigurasi terpisah untuk LinkDroid di
  `/etc/nginx/conf.d/linkdroid-api.conf`.
- Nginx meneruskan HTTPS ke `http://127.0.0.1:3000`.
- Sertifikat TLS dikelola Certbot dan konfigurasi auto-renew sudah tersedia.
- Header `Upgrade` dan `Connection: upgrade` sudah diteruskan, sehingga endpoint
  WebSocket signaling dapat menggunakan `wss://103-245-38-142.sslip.io/...`.
- `proxy_read_timeout` adalah 3600 detik untuk koneksi long-lived.
- Port internal backend yang harus dipakai adalah `3000`; port ini tidak perlu
  diekspos langsung ke internet.

### Status layanan saat ini

| Komponen | Status aktual | Catatan |
| --- | --- | --- |
| Nginx | Tersedia | Konfigurasi LinkDroid terpisah dan sudah mengarah ke port 3000. |
| HTTPS/TLS | Tersedia | Sertifikat dikelola Certbot. |
| Backend LinkDroid | Source siap, belum berjalan di VPS | API Express/Prisma/WebSocket sudah dibuat; port 3000 VPS masih kosong dan ini penyebab `502 Bad Gateway`. |
| PostgreSQL service | Tersedia dan berjalan | Hanya listen di `127.0.0.1:5432`. |
| Database/user LinkDroid | Belum dibuat | Harus dibuat terpisah dari database aplikasi lain. |
| coturn TURN/STUN | Berjalan | Listen di port 3478 TCP/UDP; realm dan metode kredensial masih perlu dikonfirmasi. |
| Node.js | Tersedia | Versi yang tercatat di VPS adalah v24.20.0. |
| PM2 | Tersedia | Proses LinkDroid belum berjalan; rencana nama proses `linkdroid-backend`. |

### Rencana penempatan backend

Backend LinkDroid direncanakan berada di folder terpisah:

`/root/linkdroid-backend`

Nama proses PM2 yang direncanakan:

`linkdroid-backend`

Proses tersebut harus berjalan berdampingan dengan aplikasi lain tanpa mengubah
konfigurasi atau proses aplikasi lain. Perintah start baru dapat dijalankan
setelah source backend dan hasil build tersedia.

### TURN/STUN untuk WebRTC

coturn sudah berjalan di port `3478` pada IP publik VPS dan localhost. Aplikasi
Android nantinya dapat menggunakan ICE server seperti:

`turn:103.245.38.142:3478`

Namun isi `/etc/turnserver.conf` belum tercatat dalam repository, sehingga
`realm`, `static-auth-secret`, username/password, dan dukungan `turns:` belum
terkonfirmasi. Kredensial TURN tidak boleh ditulis di source code atau
`SYSTEM_GAPS.md`.

### Keamanan VPS

- Server shared/multi-tenant harus diperlakukan sebagai lingkungan yang tidak
  boleh diubah secara global untuk kebutuhan LinkDroid.
- Terdapat catatan percobaan login SSH yang gagal dan traffic scan bot pada
  aplikasi lain. Ini bukan bukti kompromi domain LinkDroid, tetapi menunjukkan
  kebutuhan hardening.
- Password database, JWT secret, kredensial TURN, dan secret aplikasi harus
  disimpan melalui environment/secrets, bukan di repository.
- Hardening SSH dan fail2ban masih menjadi pekerjaan operasional yang belum
  selesai.

## Ringkasan kondisi saat ini

Fondasi aplikasi Android yang sudah tersedia:

- Aplikasi native Kotlin dengan Jetpack Compose.
- Login lokal/demo dengan validasi email dan panjang password.
- Pilihan peran lokal `Admin` atau `Petugas` saat login.
- Navigasi Beranda, Perangkat, Sesi Remote, dan Pengaturan.
- Dashboard Admin untuk memasukkan ID device petugas dan memulai permintaan
  pemantauan.
- Beranda Petugas yang menampilkan ID device lokal untuk dibagikan kepada Admin.
- Form Data pelanggan untuk:
  - Nama Lengkap Pelanggan sesuai KTP/Rekening Listrik.
  - Nomor Meter / ID Pelanggan (IDPEL) 11–12 digit.
  - Alamat Lengkap.
  - Desa / Kelurahan.
  - Kecamatan.
  - Kabupaten / Kota.
  - Provinsi melalui dropdown.
- Tombol `Demo ID` untuk mengisi contoh IDPEL `532819004521`.
- Validasi form dan tombol `Kembali` serta `Simpan & Lanjut`.
- Layar tahap PLN Mobile dengan status data siap diproses dan kontrol
  MediaProjection untuk berbagi layar.
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
- Backend Node.js di `backend/` sudah memiliki JWT auth/refresh, pairing device,
  heartbeat, task pelanggan, session lifecycle, audit log, Prisma schema dan
  migration, serta WebSocket signaling.
- APK sudah memanggil auth server, pairing device ber-token, heartbeat,
  request/approve/reject session, submit task pelanggan, dan list task Admin.
- Perintah Run Replit untuk menjalankan `gradle assembleDebug`; belum ada
  workflow server atau workflow Android yang berhasil berjalan.

Namun, aplikasi belum menjadi sistem remote-support end-to-end. REST session dan
WebSocket approval sudah ditulis, tetapi media WebRTC, data channel kontrol,
deployment VPS, dan pengujian dua perangkat fisik belum selesai.

## Matriks kesiapan launch

| Area | Status | Keterangan |
| --- | --- | --- |
| Repository | Siap secara struktur | Satu repo dengan satu project Gradle Android sebagai source aplikasi; folder metadata artefak import bukan source backend atau web. |
| Konfigurasi build | Tersedia | `.replit` menjalankan `gradle assembleDebug` dan Java/Gradle tersedia. |
| Build APK | Gagal di environment ini | Build berhenti sebelum kompilasi karena `ANDROID_HOME`/`ANDROID_SDK_ROOT` tidak ada dan Android SDK platform 35 tidak tersedia. |
| UI/menu | Terimplementasi, belum diuji runtime | Kode memiliki login Admin/Petugas, dashboard monitoring, form data pelanggan, tahap PLN Mobile, Perangkat, Sesi Remote, dan Pengaturan, tetapi belum ada APK/emulator untuk membuktikan alurnya berjalan. |
| Login produksi | API/client tersedia, belum diuji di VPS | JWT email/password, refresh token, dan role sudah dibuat; akun Admin membutuhkan invite code dan database aktif. |
| Form data pelanggan | UI, validasi, API, dan list Admin tersedia | Petugas mengirim task tervalidasi ke backend; status dan alur PLN Mobile tetap perlu diuji runtime. |
| Alur Admin–Petugas | Request/approval tersedia, media belum ada | Request session sudah melalui REST/WebSocket; layar video dan kontrol gesture belum melewati WebRTC. |
| Tahap PLN Mobile | Layar panduan tersedia | Aplikasi belum membuka, membaca, atau memverifikasi status aplikasi PLN Mobile; penyelesaian masih manual. |
| Device registration | Backend/client tersedia, belum dideploy | APK mendaftarkan device memakai access token dan heartbeat; endpoint VPS belum aktif. |
| Screen capture lokal | Terimplementasi, belum diuji perangkat | MediaProjection dan foreground service sudah dibuat; belum ada pengiriman frame dan belum diverifikasi di perangkat fisik. |
| Remote control | Belum siap | Accessibility Service tersedia, tetapi belum menerima command dari peer. |
| Signaling | Backend/client tersedia, belum diuji antar-device | Server merelay offer/answer/ICE untuk session yang disetujui; deployment dan uji dua device belum dilakukan. |
| WebRTC/video | Belum tersedia | Frame belum di-encode atau dikirim. |
| Audio | Belum tersedia | Toggle ditampilkan nonaktif agar tidak mengklaim fitur yang belum ada. |
| TLS/domain | Belum siap | Endpoint yang diberikan mengembalikan 502 saat pengecekan. |
| Release signing | Belum tersedia | Belum ada keystore dan pipeline AAB/release. |
| QA launch | Belum siap | Belum ada unit/instrumented test, APK yang berhasil dibuat di environment ini, atau uji perangkat fisik. |

### Alur yang sudah ditulis di kode, tetapi belum terverifikasi runtime

Kode dimaksudkan untuk mendukung alur berikut setelah APK berhasil dibuat dan
dijalankan pada emulator/perangkat:

1. Masuk atau daftar menggunakan email valid dan password minimal 8 karakter
   melalui backend JWT, lalu memilih role `Admin` atau `Petugas`. Tombol akun
   contoh hanya mengisi field; tidak melewati autentikasi server.
2. Sebagai Admin, memasukkan ID device Petugas pada dashboard Monitoring dan
   menekan `Hubungkan & Pantau`.
3. Sebagai Petugas, melihat ID device lokal dan membagikannya kepada Admin.
4. Sebagai Petugas, membuka formulir dan mengisi nama pelanggan, IDPEL, alamat,
   desa/kelurahan, kecamatan, kabupaten/kota, dan provinsi.
5. Menggunakan `Demo ID` untuk mengisi IDPEL contoh, memilih provinsi dari
   dropdown, lalu menekan `Simpan & Lanjut`.
6. Melihat ringkasan data pada tahap PLN Mobile dan meminta izin MediaProjection
   untuk berbagi layar.
7. Membuka setiap menu bawah sesuai role: Admin memiliki `Monitoring`,
   `Perangkat`, dan `Pengaturan`; Petugas memiliki `Tugas` dan `Pengaturan`.
8. Mendaftarkan device 9 digit ke backend, mengirim heartbeat, mencegah duplikat
   lokal, dan menghapus perangkat tersimpan.
9. Menyalakan/mematikan foreground capture, mengelola Accessibility Service,
   mengaktifkan/mematikan notifikasi sesi, mengakhiri sesi, dan logout.

Daftar di atas adalah kemampuan yang terlihat dari source code, bukan hasil
pengujian berhasil pada perangkat. Pengujian di atas juga belum berarti alur
Admin–Petugas atau remote support antarperangkat sudah bekerja. Tombol
`Hubungkan & Pantau` sekarang membuat request session ke backend dan petugas
melihatnya lewat WebSocket untuk disetujui atau ditolak. Data pelanggan dikirim
ke backend dan dapat dimuat Admin. Koneksi video dan kontrol tetap belum aktif
karena pipeline WebRTC/data channel belum diimplementasikan.

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

Client APK sekarang mengirim dengan header `Authorization: Bearer <accessToken>`:

`POST https://103-245-38-142.sslip.io/api/v1/devices/register`

Dengan JSON:

```json
{
  "deviceId": "ID lokal 9 digit",
  "deviceName": "manufacturer dan model Android",
  "androidVersion": "versi Android",
  "appVersion": "versi aplikasi"
}
```

Respons HTTP `2xx` dianggap berhasil dan statusnya ditampilkan sebagai
`Device sudah terdaftar di server`. Respons non-`2xx`, termasuk `502`, dianggap
gagal dan ditampilkan di menu Pengaturan sehingga pengguna dapat mencoba
pendaftaran ulang. Endpoint tersedia di source backend, tetapi belum
terverifikasi pada VPS.

## Prioritas kritis

### 1. Signaling backend sudah ditulis, tetapi belum operasional

Backend dan client sudah menyediakan kanal untuk:

- Mendaftarkan device ID ke server.
- Menemukan perangkat tujuan.
- Mengirim permintaan sesi.
- Menerima atau menolak permintaan sesi.
- Menyinkronkan status sesi.
- Menangani reconnect dan sesi yang kedaluwarsa.

Yang belum terverifikasi adalah deployment VPS, database aktif, reconnect,
expiry scheduler, dan alur pada dua device fisik.

### 2. Transport WebRTC belum tersedia

Belum ada implementasi WebRTC atau transport real-time lain untuk mengirim:

- Frame layar dari `MediaProjection`.
- Audio sesi.
- Data channel untuk perintah remote.
- Status koneksi, latency, dan kualitas jaringan.

`ScreenCaptureService` saat ini membuat VirtualDisplay dan membaca frame agar
buffer tidak penuh, tetapi frame tersebut belum dikodekan dan dikirim ke
perangkat lain.

### 3. Alur penerima sesi sudah tersedia untuk approval

Device penerima sekarang dapat:

- Melihat permintaan sesi masuk.
- Memverifikasi identitas pengendali.
- Menyetujui atau menolak akses melalui tombol UI.
- Melihat indikator monitoring aktif setelah approval.

Yang masih belum ada: penghentian sesi penerima melalui tombol khusus dan
indikator media WebRTC yang benar-benar tersambung.

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

### 5. Login server sudah tersedia, tetapi belum siap produksi

Login APK sekarang memanggil API JWT dan menyimpan access/refresh token untuk
sesi aktif. Sistem produksi masih membutuhkan:

- Penggantian access token otomatis ketika kedaluwarsa.
- Logout dari semua perangkat.
- Reset password.
- Verifikasi email.
- Manajemen akun pengguna.

Sistem produksi membutuhkan provider autentikasi atau backend yang aman.

### 6. Device ID sudah memiliki pairing server, tetapi belum terdeploy

ID perangkat utama dibuat lokal sekali lalu dipairing melalui endpoint
terautentikasi. Backend menyimpan device, heartbeat, dan pencabutan device.
Fitur ini belum dapat dinyatakan berjalan sebelum migration diterapkan di
PostgreSQL VPS dan backend listen di port 3000.

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

### 10. Otorisasi sesi tersedia di backend, belum diuji produksi

JWT access token, refresh token, role check, device ownership, participant check,
dan persetujuan eksplisit penerima sudah diterapkan di backend. Device ID saja
tidak menjadi kredensial akses.

Sistem produksi masih membutuhkan:

- Token sesi berumur pendek.
- Persetujuan eksplisit penerima.
- Validasi identitas kedua pihak.
- Pembatasan satu sesi aktif jika diperlukan.
- Pencabutan akses.
- Proteksi replay attack.

### 11. Enkripsi media end-to-end belum diverifikasi

API dan WebSocket dirancang untuk HTTPS/WSS melalui domain TLS, tetapi pipeline
media WebRTC belum ada sehingga enkripsi media dan validasi identitas peer belum
dapat diuji.

### 12. Audit log backend tersedia, riwayat operasional belum lengkap

Backend sudah mencatat event akun, device, task, dan lifecycle session. Belum ada
pencatatan:

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

### 15. Error handling jaringan sebagian tersedia

Client API menampilkan error HTTP/auth dan signaling menampilkan error koneksi.
Yang masih belum ditangani lengkap:

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

### 18. Backend sudah dibuat, tetapi belum operasional di VPS

Lapisan VPS dasar sudah tersedia, source backend dan migration sudah ada, tetapi
deployment belum dilakukan. Sistem produksi masih membutuhkan:

- Proses backend yang listen di `127.0.0.1:3000`; Nginx dan TLS sudah
  meneruskan traffic ke lokasi tersebut.
- Database dan user PostgreSQL khusus LinkDroid; service PostgreSQL sudah
  berjalan di localhost.
- Penerapan migration dan handler API/WebSocket/health check yang sudah tersedia.
- Penyimpanan konfigurasi.
- Rate limiting.
- Monitoring dan logging.
- Backup dan pemulihan.
- Mekanisme upgrade schema.

`502 Bad Gateway` saat ini disebabkan port 3000 kosong, bukan karena konfigurasi
Nginx atau sertifikat TLS belum tersedia.

### 19. Konfigurasi endpoint dan kontrak sudah terhubung di source

Endpoint domain produksi dicatat sebagai `BuildConfig`, client API Android
memakai kontrak REST JWT, dan client WebSocket memakai kontrak signaling.
Backend melayani kontrak tersebut di source, tetapi belum tersedia pada domain
karena belum dideploy. Konfigurasi WebRTC yang memakai coturn, logging produksi,
dan mode development/production tetap perlu diuji. Nilai rahasia tidak boleh
ditulis langsung ke source code atau di-commit ke repository.

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

1. Deploy backend, terapkan migration, dan aktifkan health check domain.
2. Uji login, pairing device, heartbeat, dan auth refresh.
3. Uji alur permintaan, persetujuan, penolakan, dan penghentian sesi pada dua
   perangkat.
4. Tambahkan WebRTC untuk video layar dan data channel.
5. Hubungkan perintah remote ke Accessibility Service dengan validasi ketat.
6. Tambahkan audio jika memang diperlukan oleh produk.
7. Perkuat penyimpanan token, lifecycle, audit log, dan error handling.
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

Repository ini sudah memiliki fondasi UI/service Android native Kotlin, backend
JWT/Prisma, API task/session, dan signaling WebSocket. Auth, pairing device,
approval session, heartbeat, dan penyimpanan task sudah terhubung di source.
Aplikasi belum memenuhi kebutuhan remote-support produksi karena backend belum
dideploy ke VPS, WebRTC video/data channel dan command Accessibility belum ada,
build APK belum dapat diverifikasi tanpa Android SDK, serta belum ada uji dua
perangkat fisik. Aplikasi belum boleh dipasarkan sebagai remote-support aktif
sampai checklist di atas selesai.
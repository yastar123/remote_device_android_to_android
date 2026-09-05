# Status Sistem LinkDroid Saat Ini

Dokumen ini menggambarkan kondisi sistem **apa adanya berdasarkan source code
dan log deployment VPS yang diberikan pemilik sistem**. Status production yang
dicantumkan di sini berasal dari log tersebut; pengujian APK, dua perangkat,
dan WebRTC end-to-end tetap dibedakan sebagai pengujian terpisah.

**Pembaruan terakhir:** 6 September 2026

## 1. Struktur project yang sebenarnya

Repository ini berisi:

- `artifacts/linkdroid-android/native-kotlin/` — satu project Android native
  Kotlin dengan Jetpack Compose.
- `backend/` — source backend Node.js/TypeScript dengan Express, Prisma,
  PostgreSQL, JWT, dan WebSocket.
- `artifacts/linkdroid-android/.replit-artifact/artifact.toml` — metadata
  artifact Android.
- `.replit` — berisi perintah build APK:
  `cd artifacts/linkdroid-android/native-kotlin && gradle assembleDebug`.

Tidak ada source website atau workspace Expo yang menjadi bagian dari sistem
ini. Backend yang aktif secara source berada di folder root `backend/`, bukan
di dalam artifact Android.

## 2. Status yang dapat dipastikan dari repository

| Komponen | Status dari source | Batasan |
| --- | --- | --- |
| Aplikasi Android | Ada | Belum berarti berhasil dibuild atau diuji pada perangkat. |
| UI Compose | Ada | Alur runtime belum dibuktikan dengan APK/emulator dari repository ini. |
| Backend Express | Ada, berhasil dibuild lokal, dan dilaporkan LIVE di VPS | Production berjalan di belakang Nginx/PM2; source repo tidak memuat environment production. |
| Prisma schema dan migration | Ada; Prisma Client berhasil dibuat lokal | Migration initial dilaporkan sudah diterapkan ke PostgreSQL production. |
| JWT access/refresh token | Ada di backend dan client | Client memakai Keystore, refresh otomatis, rotasi, dan logout. |
| Device registration dan heartbeat | Ada di backend/client utama | Backend production aktif menurut log VPS; alur dari APK production belum diuji satu per satu. |
| REST session lifecycle | Ada dengan guard transisi status | Sesi mengatur request, approval, active, reject, end, dan expiry; keberhasilan lintas dua device belum dibuktikan. |
| WebSocket signaling relay | Ada dengan ping, aktivasi, reconnect, SDP/ICE relay, command, dan acknowledgment | Signaling bukan media transport; koneksi WebRTC tetap perlu diuji pada dua device dan jaringan nyata. |
| Screen capture lokal | Ada | Mode legacy hanya membaca lalu membuang frame; mode WebRTC membuat screen video track, tetapi belum teruji dua device. |
| Accessibility service | Ada dan menerima command remote | Command tap, swipe, text, back, dan home divalidasi, dibatasi pada session, serta mengembalikan acknowledgment melalui signaling WebSocket. |
| WebRTC/video | Ada implementasinya | Android memiliki `PeerConnection`, screen video track, offer/answer, ICE candidate, renderer controller, dan data channel; belum ada bukti runtime dua device. |
| Audio remote | Tidak ada | Tidak ada audio source, audio track, atau audio renderer. |
| VPS/Nginx/TLS/PM2 | LIVE menurut log deployment | Health check production berhasil; konfigurasi server berada di luar repository. |
| PostgreSQL production | LIVE menurut log deployment | Database `LINKDROID` dan migration initial dilaporkan berhasil; kredensial tidak disimpan di repo. |
| TURN/coturn | Terpasang di VPS | TURN credential masih perlu diganti dan koneksi WebRTC nyata belum diverifikasi. |
| Workflow Replit | Belum dikonfigurasi | File `.replit` punya run command, tetapi snapshot project tidak memiliki workflow aktif; build Android juga membutuhkan SDK yang tidak disimpan di repo. |
| Release build/signing | Belum ada | Belum ada keystore, signing config, AAB, atau pipeline release. |

## 3. Aplikasi Android yang sudah tersedia

### UI dan navigasi

Source Android menyediakan:

- Login dan pendaftaran akun melalui backend.
- Pemilihan role `Admin` atau `Petugas` pada pendaftaran.
- Navigasi Admin:
  - Monitoring.
  - Perangkat.
  - Pengaturan.
- Navigasi Petugas:
  - Tugas.
  - Pengaturan.
- Form data pelanggan dengan field:
  - nama lengkap;
  - nomor meter/IDPEL 11–12 digit;
  - alamat;
  - desa/kelurahan;
  - kecamatan;
  - kabupaten/kota;
  - provinsi.
- Tombol `Demo ID` dengan contoh IDPEL `532819004521`.
- Layar tahap PLN Mobile yang menampilkan data yang sudah dikirim dan status
  berbagi layar.
- Dialog masuk untuk permintaan monitoring pada perangkat Petugas.
- Tombol menyetujui atau menolak permintaan monitoring.
- Tombol menghentikan sesi dan berbagi layar.
- Pengaturan permission notifikasi, status Accessibility Service, dan status
  pendaftaran device.

### Penyimpanan lokal

Aplikasi menggunakan `Activity.getPreferences(MODE_PRIVATE)` untuk menyimpan
metadata lokal non-secret:

- email;
- role;
- ID device lokal;
- daftar device lokal;
- draft data pelanggan yang sedang diisi;
- preferensi notifikasi.

Access token dan refresh token disimpan terpisah oleh `SecureTokenStore.kt`
dengan enkripsi Android Keystore. Jika instalasi lama masih memiliki token di
preferences, token tersebut dimigrasikan satu kali lalu dihapus dari lokasi
lama.

Daftar device lokal disimpan sebagai `StringSet` dengan data yang digabung
menggunakan separator `~`. Belum ada Room, database lokal, atau migrasi format
untuk metadata tersebut.

ID device lokal dibuat sekali menggunakan angka acak 9 digit dan disimpan di
preferences aplikasi. ID ini bukan kredensial akses; backend tetap memerlukan
Bearer access token dan memeriksa kepemilikan device.

### Permission dan service Android

Manifest mendeklarasikan:

- `INTERNET`;
- `FOREGROUND_SERVICE`;
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`;
- `POST_NOTIFICATIONS`;
- `ScreenCaptureService`;
- `RemoteAccessibilityService`.

`ScreenCaptureService`:

- meminta hasil persetujuan MediaProjection dari Activity;
- membuat foreground service;
- pada mode capture legacy, membuat `VirtualDisplay` dan `ImageReader`, lalu
  mengambil image terbaru dan langsung menutupnya;
- pada mode WebRTC, menjaga foreground service tetap hidup sementara
  `WebRtcSessionManager` memiliki `ScreenCapturerAndroid` dan mengirimkan video
  track ke `PeerConnection`;
- menghentikan capture saat service dihentikan atau projection berhenti.

Jalur WebRTC sekarang meng-encode dan mengirim frame melalui video track. Jalur
legacy `ScreenCaptureService` sendiri tetap bukan pipeline video karena frame
`ImageReader` tidak disimpan atau dikirim.

`RemoteAccessibilityService`:

- terdaftar sebagai Accessibility Service;
- tidak mengolah accessibility event;
- memiliki fungsi gesture tap/swipe berbasis koordinat relatif;
- dapat mengisi text pada field yang sedang fokus;
- dapat menjalankan global action Back dan Home;
- mengembalikan acknowledgment berhasil/gagal kepada pemanggil.

Jalur WebRTC data channel dan fallback signaling WebSocket dapat memanggil
service ini setelah command lolos validasi.

## 4. Backend yang benar-benar tersedia

### Konfigurasi yang wajib

Backend memvalidasi environment berikut saat proses dimulai:

- `DATABASE_URL` — wajib.
- `JWT_SECRET` — wajib dan minimal 32 karakter.
- `PORT` — default `3000`.
- `HOST` — default `127.0.0.1`.
- `JWT_ISSUER` — default `linkdroid-api`.
- `ACCESS_TOKEN_TTL` — default `15m`.
- `REFRESH_TOKEN_DAYS` — default `30`.
- `CORS_ORIGIN` — default `*`.
- `ADMIN_INVITE_CODE` — opsional, tetapi dibutuhkan untuk mendaftarkan role
  `ADMIN`.
- `TURN_URLS`, `TURN_USERNAME`, `TURN_CREDENTIAL` — opsional di source;
  environment production VPS dilaporkan sudah diisi dan coturn berjalan, tetapi
  kredensial kerja masih perlu diganti dari nilai placeholder.

Nilai rahasia tidak boleh ditulis ke repository atau dokumen ini.

### Endpoint yang tersedia

Endpoint publik:

- `GET /health` — menjalankan `SELECT 1` ke database dan mengembalikan status
  database.
- `POST /api/v1/auth/register`.
- `POST /api/v1/auth/login`.
- `POST /api/v1/auth/refresh`.

Endpoint dengan Bearer access token:

- `POST /api/v1/auth/logout` — mencabut semua refresh token aktif milik user.
- `GET /api/v1/me`.
- `POST /api/v1/devices/register`.
- `GET /api/v1/devices`.
- `DELETE /api/v1/devices/:deviceId`.
- `POST /api/v1/devices/:deviceId/heartbeat`.
- `POST /api/v1/sessions` — khusus `ADMIN`.
- `GET /api/v1/sessions`.
- `POST /api/v1/sessions/:id/approve` — hanya receiver.
- `POST /api/v1/sessions/:id/reject` — hanya receiver.
- `POST /api/v1/sessions/:id/end` — salah satu participant.
- `POST /api/v1/tasks` — khusus `WORKER`.
- `GET /api/v1/tasks`.
- `PATCH /api/v1/tasks/:id/status`.
- `GET /api/v1/audit-logs` — khusus `ADMIN`, dengan filter action dan cursor.
- `GET /api/v1/turn/credentials`.

Backend juga memasang Helmet, CORS, body size limit `128kb`, rate limit umum,
dan rate limit auth. Express mempercayai proxy loopback karena backend berada di
belakang Nginx pada host yang sama. Error validasi Zod dikembalikan sebagai
`400`.

### Database

Schema Prisma berisi model:

- `User`;
- `Device`;
- `RefreshToken`;
- `RemoteSession`;
- `CustomerTask`;
- `AuditLog`.

Status session yang didefinisikan adalah `REQUESTED`, `APPROVED`, `ACTIVE`,
`REJECTED`, `ENDED`, dan `EXPIRED`. Endpoint approval mengubah status menjadi
`APPROVED`, koneksi WebSocket pada session yang disetujui mengaktifkannya menjadi
`ACTIVE`, sedangkan end mengubahnya menjadi `ENDED`. Backend juga memiliki
scheduler expiry setiap 60 detik: request yang terlalu lama dan session yang
idle akan diubah menjadi `EXPIRED`, dicatat ke audit log, dan diberitahukan ke
participant.

Audit log ditulis untuk event register user, register/revoke device, request/
approve/reject/end session, create task, dan perubahan status task. Endpoint
admin untuk mengambil audit log dan pagination cursor sudah tersedia, dan
dashboard Admin menampilkan aktivitas terbaru. Filter lanjutan dan export audit
log belum tersedia.

### WebSocket signaling

Endpoint upgrade `/ws` memerlukan query parameter:

- `access_token`;
- `device_id`.

Server:

- memverifikasi access token;
- memastikan device terdaftar pada user tersebut dan belum dicabut;
- merelay `offer`, `answer`, dan `ice-candidate`;
- hanya mengizinkan device yang menjadi controller atau receiver session;
- hanya mengizinkan session berstatus `APPROVED` atau `ACTIVE`.

Pesan `session.ping` divalidasi terpisah, memperbarui aktivitas session, dan
mendapat balasan `session.pong`. Android memakai ping tersebut untuk menjaga
session aktif. Client Android juga melakukan reconnect otomatis dengan
exponential backoff sampai 30 detik.

## 5. Integrasi Android dengan backend

`BackendApiClient` yang dipakai alur utama Android sudah memanggil:

- register/login;
- register device;
- mengambil daftar device dari backend dan menghitung status online dari
  `lastSeenAt`;
- heartbeat setiap 30 detik selama token ada;
- membuat session;
- approve/reject/end session;
- membuat task pelanggan;
- mengubah status task;
- mengambil daftar task untuk Admin;
- mengambil audit log terbaru untuk dashboard Admin;
- mengambil konfigurasi ICE server dari backend jika tersedia.

`SignalingClient` membuat koneksi WebSocket dan menangani:

- koneksi berhasil;
- session request;
- session approved/active/rejected/ended/expired;
- ping/pong dan reconnect dengan exponential backoff;
- error signaling.

`MainActivity` membuat `WebRtcSessionManager` untuk session aktif. Controller
membuat offer setelah session disetujui; receiver membuat answer setelah
menerima offer; keduanya mengirim dan menerapkan ICE candidate melalui
`SignalingClient.sendSignal`. Controller juga menampilkan remote video melalui
`SurfaceViewRenderer`.

`WebRtcSessionManager` memiliki data channel untuk command dan acknowledgment.
Alur utama mencoba data channel terlebih dahulu lalu masih memakai signaling
WebSocket sebagai fallback. Karena itu, implementasi data channel sudah ada,
tetapi ketergantungan fallback dan keberhasilan runtime tetap perlu diuji.

Ada file `DeviceRegistrationClient.kt` yang mengirim payload lama tanpa header
Bearer dan tidak dipakai oleh alur utama `MainActivity`. Kontrak yang dipakai
alur utama adalah `BackendApiClient`. Endpoint audit log juga sudah memiliki
client Android dan ditampilkan sebagai aktivitas terbaru pada dashboard Admin.

## 6. Gap fungsional yang nyata

### A. Remote support end-to-end masih belum terverifikasi

Yang sudah ada hanya:

1. Admin membuat request session.
2. Backend menyimpan request.
3. WebSocket memberitahukan request kepada Petugas.
4. Petugas approve atau reject.
5. Backend mengirim event status kepada participant.
6. Android membuat `PeerConnection` setelah session aktif.
7. Receiver mengambil screen capture dengan `ScreenCapturerAndroid` setelah
   izin MediaProjection diberikan.
8. Controller membuat offer; receiver menjawab dengan answer; ICE candidate
   diteruskan melalui signaling WebSocket.
9. Controller memiliki renderer untuk remote video.
10. Controller dapat mengirim command melalui data channel, dengan fallback ke
    signaling WebSocket; receiver menjalankan command dan mengirim hasil.

Yang belum bisa diklaim hanya dari source:

- koneksi video dua device yang berhasil pada runtime;
- keberhasilan ICE/STUN/TURN pada jaringan yang relevan;
- metrik latency, bitrate, frame rate, dan kualitas koneksi;
- pemulihan `PeerConnection` setelah process death, background, atau
  perubahan jaringan.

Tombol atau pesan “menunggu koneksi media” juga tidak sama dengan bukti bahwa
koneksi media sudah aktif.

### B. Kontrol remote melalui WebRTC sudah ditulis, tetapi fallback masih ada

Accessibility Service menerima command remote melalui dua jalur:

- data channel WebRTC jika channel sudah `OPEN`;
- signaling WebSocket sebagai fallback.

Kedua jalur memakai:

- schema command `tap`, `swipe`, `text`, `back`, dan `home`;
- validasi koordinat rasio `0..1`, durasi gesture, dan panjang text;
- pembatasan command controller kepada receiver pada session `APPROVED` atau
  `ACTIVE`;
- timeout command 15 detik di signaling hub;
- acknowledgment berhasil/gagal dari perangkat receiver;
- implementasi gesture dan global action pada Accessibility Service.

Gap yang tersisa adalah membuktikan data channel menjadi jalur normal pada
koneksi nyata dan menentukan apakah fallback signaling dipertahankan sebagai
mode pemulihan atau dihapus setelah stabil.

### C. Auth client masih memiliki batasan

Android sekarang:

- menyimpan access dan refresh token terenkripsi menggunakan Android Keystore;
- memigrasikan token plaintext lama satu kali lalu menghapus salinan lama;
- otomatis memanggil refresh dan menyimpan token hasil rotasi saat request
  authenticated menerima HTTP 401;
- memanggil endpoint logout dan tetap menghapus token lokal.

Backend juga belum memiliki reset password, verifikasi email, atau manajemen
akun.

### D. Lifecycle sesi belum tahan terhadap process death

State utama session, screen sharing, task yang baru dibuat, dan pesan UI berada
di state Activity/Compose. Metadata session ID, peer device ID, role, daftar
device server, dan draft data pelanggan kini dipersist atau disinkronkan.
Pemulihan penuh capture service, token refresh lintas process, dan koneksi
WebRTC setelah process death masih belum tersedia.

Backend sudah memiliki scheduler expiry dan client sudah memiliki retry/backoff
WebSocket. Yang masih belum selesai adalah pemulihan penuh state session,
screen sharing, WebRTC, dan service setelah process death atau service berjalan
terpisah.

### E. Notifikasi masih dasar

Notifikasi permission diminta pada Android 13+. Aplikasi dapat membuat
notifikasi lokal untuk status menunggu sesi, request monitoring masuk, sesi
aktif/berakhir, dan foreground notification untuk screen capture.

Belum ada:

- notifikasi push ketika aplikasi tidak aktif;
- deep link menuju session tertentu;
- notifikasi lifecycle lengkap untuk request, approve, reject, disconnect, dan
  expiry;
- jaminan sinkronisasi notifikasi lintas process.

### F. Tahap PLN Mobile masih manual

Aplikasi hanya menampilkan panduan dan ringkasan data. Source tidak:

- membuka aplikasi PLN Mobile;
- membaca UI PLN Mobile;
- memverifikasi hasil proses PLN Mobile;
- menyimpan bukti hasil proses;
- mengubah status berdasarkan observasi aplikasi PLN Mobile secara otomatis.

## 7. Gap keamanan dan privasi

Fondasi yang sudah ada:

- password di-hash dengan bcrypt;
- access token JWT memiliki expiry;
- refresh token disimpan sebagai hash dan dirotasi ketika digunakan;
- role check pada endpoint tertentu;
- ownership check untuk device dan session;
- persetujuan receiver diperlukan sebelum signaling session diterima;
- device ID sendiri bukan kredensial;
- rate limit auth dan endpoint umum;
- Helmet aktif;
- screen capture membutuhkan persetujuan MediaProjection;
- Accessibility Service harus diaktifkan melalui pengaturan Android.

Yang masih perlu diselesaikan atau diverifikasi:

- rotasi seluruh credential production yang tercantum atau pernah tercantum
  pada log deployment;
- konfigurasi CORS production yang tidak memakai wildcard bila tidak diperlukan;
- penggunaan database user non-superuser;
- hardening SSH root dan pemasangan fail2ban;
- audit dependency secara penuh dan penanganan vulnerability;
- proteksi replay dan token/session yang lebih spesifik;
- policy retensi dan penghapusan data pelanggan;
- privacy policy dan consent screen capture/accessibility;
- mekanisme pelaporan penyalahgunaan;
- audit terhadap metadata customer task yang mengandung data pribadi;
- hardening, backup, monitoring, dan incident response deployment.

Log deployment yang diberikan berisi material credential production. File log
tersebut tidak boleh di-commit atau dibagikan, dan semua credential yang
terekspos harus diganti melalui mekanisme secret/environment di VPS.

## 8. Gap build dan verifikasi

Project Android menggunakan:

- compile/target SDK 35;
- min SDK 26;
- Java/Kotlin target 17;
- Android Gradle Plugin;
- Jetpack Compose Material 3.

Build yang didokumentasikan:

```bash
cd artifacts/linkdroid-android/native-kotlin
gradle assembleDebug
```

Output yang diharapkan:

```text
artifacts/linkdroid-android/native-kotlin/app/build/outputs/apk/debug/app-debug.apk
```

Repository ini tidak menyimpan Android SDK atau `local.properties`. Karena itu,
build hanya dapat dilakukan pada environment yang memiliki Android SDK platform
35 dan build-tools yang sesuai.

Belum ada bukti di repository ini untuk:

- build APK berhasil pada environment saat ini;
- emulator atau perangkat fisik terhubung;
- unit test Kotlin;
- instrumented test;
- Android lint;
- backend integration test;
- pengujian dua akun dan dua device;
- pengujian permission/background/battery restriction;
- pengujian koneksi WebSocket melalui proxy;
- pengujian TURN atau WebRTC;
- release signing/AAB.

Perintah backend yang tersedia di `package.json`:

```bash
npm run backend:prisma:generate
npm run backend:prisma:migrate
npm run backend:build
npm run backend:dev
npm run backend:start
```

`package-lock.json` sudah diregenerasi dari registry npm publik sehingga tidak
lagi bergantung pada URL registry internal Replit.

Backend membutuhkan environment runtime yang berisi `DATABASE_URL` dan
`JWT_SECRET` minimal, serta database PostgreSQL yang sudah dibuat. Template yang
tersedia ada di `backend/.env.example`; lokasi file `.env` yang digunakan oleh
PM2 harus dipastikan sesuai dengan working directory proses. Tidak ada nilai
production yang boleh diasumsikan dari source.

### Verifikasi backend lokal yang tercatat

Verifikasi berikut berhasil dilakukan pada workspace, bukan pada VPS:

- `npm run backend:build` berhasil.
- `npm run backend:prisma:generate` berhasil dengan Prisma Client `6.19.0`.
- `npm run backend:prisma:format` berhasil.
- Backend berhasil listen pada `127.0.0.1:3000`.
- `GET /health` mengembalikan HTTP `200` dengan `database: "up"`.
- Proses backend menerima `SIGTERM` dan melakukan shutdown dengan bersih.

Validasi lokal tidak membuktikan kondisi VPS. Berdasarkan log deployment yang
diberikan pemilik sistem, verifikasi production berikut sudah berhasil:

- repository berhasil di-clone ke VPS;
- dependency berhasil dipasang dari registry npm publik setelah lockfile lama
  yang mengarah ke registry internal diganti;
- Prisma Client berhasil dibuat dan migration initial berhasil diterapkan ke
  database `LINKDROID`;
- backend berhasil dibuild;
- PM2 menjalankan `linkdroid-backend` dan diset auto-start saat reboot;
- Nginx meneruskan HTTPS dan WebSocket ke `127.0.0.1:3000`;
- `GET https://103-245-38-142.sslip.io/health` mengembalikan HTTP `200` dengan
  database `up`;
- coturn tersedia pada VPS untuk konfigurasi TURN.

Log tersebut adalah bukti operasional yang dilaporkan, bukan pengujian yang
dijalankan ulang dari workspace ini. Nilai secret production tidak dicatat di
dokumen ini.

Pada database lokal yang belum menjalankan migration, server tetap start dan
`/health` tetap dapat menjawab, tetapi scheduler expiry memberi peringatan bahwa
tabel `RemoteSession` belum tersedia. Jalankan
`npm run backend:prisma:migrate` pada database target sebelum memakai lifecycle
session di environment tersebut.

## 9. Status deployment

Repository mendefinisikan base URL Android sebagai:

```text
https://103-245-38-142.sslip.io
```

Menurut log deployment VPS tanggal 5–6 September 2026, deployment production
sudah **LIVE dan health check berhasil**:

- HTTP diarahkan ke HTTPS;
- TLS aktif pada domain;
- Nginx melakukan reverse proxy ke `127.0.0.1:3000`;
- PM2 menjalankan proses `linkdroid-backend`;
- PostgreSQL production aktif dan migration initial sudah diterapkan;
- `GET /health` mengembalikan `200` dengan database `up`;
- konfigurasi WebSocket diteruskan oleh Nginx;
- coturn berjalan pada VPS.

Status di atas membuktikan backend dan jalur deployment dasar, bukan berarti
register/login, pairing device, video WebRTC, TURN, atau kontrol remote sudah
diuji melalui APK sungguhan.

Backend secara default listen pada `127.0.0.1:3000`. Agar dapat diakses dari
domain, dibutuhkan reverse proxy dan proses backend yang benar-benar berjalan
dengan environment serta migration yang sesuai. Source menyediakan konfigurasi
PM2 di `backend/ecosystem.config.cjs`; log VPS melaporkan proses tersebut sudah
berjalan dan tersimpan untuk auto-start.

VPS bersifat shared/multi-tenant. Proses dan layanan aplikasi lain pada server
tersebut tidak termasuk LinkDroid dan tidak diubah dalam deployment ini.

Port yang dilaporkan aktif:

- `80` dan `443` — Nginx HTTP redirect dan HTTPS reverse proxy;
- `3000` — backend LinkDroid, hanya listen pada localhost;
- `3478` dan `5349` — coturn/TURN;
- `5432` — PostgreSQL, hanya listen pada localhost.

## 10. Prioritas pekerjaan berikutnya

Urutan yang paling langsung berdasarkan gap saat ini:

1. Rotasi semua credential production yang terekspos pada log dan pastikan
   `backend/.env` serta log deployment tidak pernah masuk Git.
2. Ganti database user superuser, perketat CORS, dan hardening SSH/fail2ban.
3. Sediakan Android SDK platform 35 dan buktikan `assembleDebug`.
4. Uji register/login/refresh/logout, pairing, heartbeat, task, dan session
   dengan dua akun.
5. Build APK dan uji alur WebRTC pada dua device: permission, offer/answer,
   ICE, video renderer, data channel, fallback, dan reconnect.
6. Uji TURN/STUN serta perilaku pada jaringan seluler, background, perubahan
   jaringan, dan permission yang dicabut.
7. Perbaiki pemulihan state lintas process death, Activity, service, dan
   `PeerConnection`.
8. Tambahkan test unit, integration, instrumented, lint, dan uji perangkat
   fisik.
9. Lengkapi privacy/consent, logging, monitoring, backup, release signing, dan
   dokumentasi operasional.

## 11. Checklist launch

- [ ] Android debug APK berhasil dibuild.
- [x] Backend dapat start dengan environment valid di VPS (berdasarkan log).
- [x] PostgreSQL migration berhasil diterapkan pada database production
  (berdasarkan log).
- [x] `/health` mengembalikan database `up` di domain production (berdasarkan
  log).
- [x] HTTPS/WSS Nginx dan PM2 production berjalan (berdasarkan log).
- [ ] Register/login/refresh/logout diuji pada backend dan client.
- [ ] Device registration, heartbeat, list, dan revoke diuji.
- [ ] Request, approve, reject, dan end session diuji dengan dua akun.
- [ ] WebSocket authentication dan reconnect diuji pada environment Android.
- [ ] WebRTC video benar-benar mengirim frame ke controller pada dua device.
- [ ] TURN/STUN diuji pada jaringan yang relevan.
- [x] Command remote melalui signaling WebSocket divalidasi dan memiliki
  timeout/error response.
- [ ] Data channel command remote berhasil menjadi jalur normal setelah WebRTC
  aktif; fallback signaling juga diuji.
- [ ] Accessibility dan MediaProjection diuji pada versi Android target.
- [ ] Background, process death, orientation, dan battery restriction diuji.
- [ ] Customer task dan status lifecycle diuji.
- [ ] Unit test, integration test, instrumented test, lint, dan release build
  berhasil.
- [x] Token storage Android memakai Keystore dan refresh/logout client tersedia.
- [ ] Privacy policy, consent, abuse reporting, monitoring,
  backup, dan rollback operasional tersedia.

## Kesimpulan

LinkDroid saat ini memiliki aplikasi Android native, backend API production yang
dilaporkan LIVE, PostgreSQL production dengan migration initial, Nginx TLS/
WebSocket reverse proxy, PM2 auto-start, auth, device pairing, heartbeat,
customer task, request/approval session, audit log, relay WebSocket, WebRTC
video/data channel, serta command remote tervalidasi dengan acknowledgment.
Screen capture, Accessibility Service, signaling SDP/ICE, renderer video, dan
command data channel sudah ditulis di source.

Sistem ini belum boleh dianggap sebagai remote-support end-to-end yang
terbukti hanya karena backend production sudah live. Build APK, pengujian dua
perangkat, koneksi video runtime, TURN/STUN pada jaringan relevan, dan pemulihan
setelah gangguan belum dibuktikan. Command masih memiliki fallback signaling
WebSocket. Audio remote belum diimplementasikan. Credential production juga
harus dirotasi karena tercantum pada log deployment yang diberikan.
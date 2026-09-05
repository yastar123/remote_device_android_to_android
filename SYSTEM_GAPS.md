# Status Sistem LinkDroid Saat Ini

Dokumen ini menggambarkan kondisi repository **apa adanya berdasarkan source
code yang tersedia**, bukan klaim bahwa deployment VPS atau pengujian dua
perangkat sudah berhasil. Dokumen ini dipakai untuk membedakan kemampuan yang
memang sudah ditulis dari kemampuan yang masih berupa fondasi atau belum ada.

**Pembaruan terakhir:** 5 September 2026

## 1. Struktur project yang sebenarnya

Repository ini berisi:

- `artifacts/linkdroid-android/native-kotlin/` — satu project Android native
  Kotlin dengan Jetpack Compose.
- `backend/` — source backend Node.js/TypeScript dengan Express, Prisma,
  PostgreSQL, JWT, dan WebSocket.
- `artifacts/linkdroid-android/.replit-artifact/artifact.toml` — metadata
  artifact Android.
- `artifacts/api-server/.replit-artifact/artifact.toml` — metadata artifact
  API yang menyatakan artifact web lama sudah dihapus; ini bukan source backend
  yang aktif.
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
| Backend Express | Ada dan berhasil dibuild lokal | Tetap wajib memiliki environment dan PostgreSQL aktif saat dijalankan. |
| Prisma schema dan migration | Ada; Prisma Client berhasil dibuat lokal | Migration belum diterapkan ke database production dari workspace ini. |
| JWT access/refresh token | Ada di backend dan client | Client memakai Keystore, refresh otomatis, rotasi, dan logout. |
| Device registration dan heartbeat | Ada di backend/client utama | Belum ada bukti backend production aktif. |
| REST session lifecycle | Ada | Media dan kontrol remote belum terhubung. |
| WebSocket signaling relay | Ada dengan ping, aktivasi, dan reconnect | Android belum membuat `PeerConnection` atau mengirim signal dari alur UI. |
| Screen capture lokal | Ada | Hanya membuat VirtualDisplay dan membaca lalu membuang frame. |
| Accessibility service | Ada dan menerima command remote | Command tap, swipe, text, back, dan home divalidasi, dibatasi pada session, serta mengembalikan acknowledgment melalui signaling WebSocket. |
| WebRTC/video/audio | Tidak ada | Tidak ada dependency atau implementasi `PeerConnection`. |
| Workflow Replit | Belum dikonfigurasi | File `.replit` punya run command, tetapi snapshot project tidak memiliki workflow aktif. |
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
- membuat `VirtualDisplay`;
- membuat `ImageReader`;
- mengambil image terbaru lalu langsung menutupnya;
- menghentikan capture saat service dihentikan atau projection berhenti.

Dengan demikian, capture layar lokal memang dimulai, tetapi frame tidak
di-encode, tidak disimpan, dan tidak dikirim ke perangkat lain.

`RemoteAccessibilityService`:

- terdaftar sebagai Accessibility Service;
- tidak mengolah accessibility event;
- memiliki fungsi `tap(x, y, durationMs)`.

Tidak ada jalur network yang memanggil fungsi tap tersebut.

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
- `TURN_URLS`, `TURN_USERNAME`, `TURN_CREDENTIAL` — opsional; tanpa ketiganya
  endpoint TURN mengembalikan `503`.

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
- `GET /api/v1/turn/credentials`.

Backend juga memasang Helmet, CORS, body size limit `128kb`, rate limit umum,
dan rate limit auth. Error validasi Zod dikembalikan sebagai `400`.

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
approve/reject/end session, create task, dan perubahan status task. Belum ada
fitur UI atau endpoint khusus untuk menampilkan audit log.

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
- heartbeat setiap 30 detik selama token ada;
- membuat session;
- approve/reject/end session;
- membuat task pelanggan;
- mengubah status task;
- mengambil daftar task untuk Admin.

`SignalingClient` membuat koneksi WebSocket dan menangani:

- koneksi berhasil;
- session request;
- session approved/active/rejected/ended/expired;
- ping/pong dan reconnect dengan exponential backoff;
- error signaling.

Client tersebut memiliki method `sendSignal`, tetapi alur `MainActivity` saat ini
tidak membuat object WebRTC dan tidak memanggil method itu untuk mengirim
offer, answer, atau ICE candidate.

Ada file `DeviceRegistrationClient.kt` yang mengirim payload lama tanpa header
Bearer dan tidak dipakai oleh alur utama `MainActivity`. Kontrak yang dipakai
alur utama adalah `BackendApiClient`.

## 6. Gap fungsional yang nyata

### A. Remote support end-to-end belum ada

Yang sudah ada hanya:

1. Admin membuat request session.
2. Backend menyimpan request.
3. WebSocket memberitahukan request kepada Petugas.
4. Petugas approve atau reject.
5. Backend mengirim event status kepada participant.
6. Android menampilkan state dan dapat menjalankan screen capture lokal.

Yang belum ada:

- `PeerConnection`;
- offer/answer yang dibuat Android;
- ICE gathering dan penerapan candidate;
- pengambilan credential TURN dari Android;
- pengiriman frame layar;
- video renderer pada controller;
- data channel;
- command tap/swipe/input;
- acknowledgment command;
- metrik latency dan kualitas koneksi.

Tombol atau pesan “menunggu koneksi media” tidak sama dengan koneksi media
yang sudah aktif.

### B. Kontrol remote belum terhubung

Accessibility Service menerima command remote melalui signaling WebSocket
terautentikasi:

- schema command `tap`, `swipe`, `text`, `back`, dan `home`;
- validasi koordinat rasio `0..1`, durasi gesture, dan panjang text;
- pembatasan command controller kepada receiver pada session `APPROVED` atau
  `ACTIVE`;
- timeout command 15 detik di signaling hub;
- acknowledgment berhasil/gagal dari perangkat receiver;
- implementasi gesture dan global action pada Accessibility Service.

Command saat ini masih memakai signaling WebSocket, bukan WebRTC data channel.
Data channel tetap diperlukan agar kontrol tidak bergantung pada relay signaling.

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
di state Activity/Compose. Metadata session ID, peer device ID, dan role kini
dipersist ke preferences dan dipulihkan setelah recreation/process death.
Pemulihan penuh capture service, token refresh lintas process, dan task draft
masih belum tersedia.

Backend sudah memiliki scheduler expiry dan client sudah memiliki retry/backoff
WebSocket. Yang masih belum selesai adalah pemulihan penuh state session,
screen sharing, dan task setelah process death atau service berjalan terpisah.

### E. Notifikasi masih dasar

Notifikasi permission diminta pada Android 13+. Aplikasi dapat membuat
notifikasi lokal untuk status menunggu sesi dan foreground notification untuk
screen capture.

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

- validasi deployment HTTPS/WSS yang benar;
- konfigurasi CORS production yang tidak memakai wildcard bila tidak diperlukan;
- pembatasan dan rotasi secret;
- proteksi replay dan token/session yang lebih spesifik;
- policy retensi dan penghapusan data pelanggan;
- privacy policy dan consent screen capture/accessibility;
- mekanisme pelaporan penyalahgunaan;
- audit terhadap metadata customer task yang mengandung data pribadi;
- hardening, backup, monitoring, dan incident response deployment.

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

Backend membutuhkan `.env` di root repository yang berisi `DATABASE_URL` dan
`JWT_SECRET` minimal, serta database PostgreSQL yang sudah dibuat. Template yang
tersedia ada di `backend/.env.example`. Tidak ada nilai production yang boleh
diasumsikan dari source.

### Verifikasi backend lokal terbaru

Verifikasi berikut berhasil dilakukan pada workspace, bukan pada VPS:

- `npm run backend:build` berhasil.
- `npm run backend:prisma:generate` berhasil dengan Prisma Client `6.19.0`.
- `npm run backend:prisma:format` berhasil.
- Backend berhasil listen pada `127.0.0.1:3000`.
- `GET /health` mengembalikan HTTP `200` dengan `database: "up"`.
- Proses backend menerima `SIGTERM` dan melakukan shutdown dengan bersih.

Health check tersebut tidak membuktikan bahwa PM2, Nginx, HTTPS, TURN, atau
database production di VPS sudah aktif. Validasi lokal juga menggunakan secret
validasi sementara untuk proses tersebut; secret production tetap harus diisi
sendiri pada `.env` VPS.

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

Repository ini **tidak membuktikan** bahwa domain tersebut sedang:

- mengarah ke server yang benar;
- memiliki Nginx atau TLS yang aktif;
- meneruskan traffic ke port `3000`;
- menjalankan backend;
- memiliki database LinkDroid;
- memiliki TURN credential yang valid.

Dengan demikian, status deployment production harus dianggap **belum
terverifikasi**, bukan dianggap aktif atau dianggap pasti `502`, kecuali ada
hasil pengecekan deployment terbaru yang dicatat terpisah.

Backend secara default listen pada `127.0.0.1:3000`. Agar dapat diakses dari
domain, dibutuhkan reverse proxy dan proses backend yang benar-benar berjalan
dengan environment serta migration yang sesuai. Source menyediakan
`backend/ecosystem.config.cjs` untuk rencana PM2, tetapi file itu sendiri bukan
bukti bahwa PM2 sudah menjalankan proses.

## 10. Prioritas pekerjaan berikutnya

Urutan yang paling langsung berdasarkan gap saat ini:

1. Sediakan Android SDK platform 35 dan buktikan `assembleDebug`.
2. Sediakan PostgreSQL dan environment backend tanpa memasukkan secret ke Git.
3. Jalankan migration, backend, dan health check secara terkontrol.
4. Uji register/login/refresh/logout, pairing, heartbeat, task, dan session
   dengan dua akun.
5. Tambahkan integrasi WebRTC Android: `PeerConnection`, offer/answer, ICE,
   TURN, video frame, dan lifecycle connection.
6. Pindahkan command remote dari relay WebSocket ke WebRTC data channel dan
   tambahkan video renderer pada controller.
7. Perbaiki pemulihan state lintas process death, Activity, dan service.
8. Tambahkan test unit, integration, instrumented, lint, dan uji perangkat
   fisik.
9. Lengkapi privacy/consent, logging, monitoring, backup, release signing, dan
   dokumentasi operasional.

## 11. Checklist launch

- [ ] Android debug APK berhasil dibuild.
- [ ] Backend dapat start dengan environment valid.
- [ ] PostgreSQL migration berhasil diterapkan pada database target.
- [ ] `/health` mengembalikan database `up`.
- [ ] Register/login/refresh/logout diuji pada backend dan client.
- [ ] Device registration, heartbeat, list, dan revoke diuji.
- [ ] Request, approve, reject, dan end session diuji dengan dua akun.
- [ ] WebSocket authentication dan reconnect diuji pada environment Android.
- [ ] WebRTC video benar-benar mengirim frame ke controller.
- [ ] TURN/STUN diuji pada jaringan yang relevan.
- [x] Command remote melalui signaling WebSocket divalidasi dan memiliki
  timeout/error response.
- [ ] Data channel command remote berjalan setelah WebRTC aktif.
- [ ] Accessibility dan MediaProjection diuji pada versi Android target.
- [ ] Background, process death, orientation, dan battery restriction diuji.
- [ ] Customer task dan status lifecycle diuji.
- [ ] Unit test, integration test, instrumented test, lint, dan release build
  berhasil.
- [x] Token storage Android memakai Keystore dan refresh/logout client tersedia.
- [ ] Privacy policy, consent, abuse reporting, monitoring,
  backup, dan rollback operasional tersedia.

## Kesimpulan

LinkDroid saat ini adalah fondasi aplikasi Android native dan backend API yang
sudah memiliki auth, device pairing, heartbeat, customer task, request/
approval session, audit log, relay WebSocket, serta command remote tervalidasi
dengan acknowledgment. Screen capture lokal dan Accessibility Service sudah
terhubung ke signaling command, tetapi belum terhubung ke WebRTC video/data
transport.

Sistem ini belum dapat disebut remote-support end-to-end karena belum memiliki
WebRTC/video, data channel, deployment yang terverifikasi, dan pengujian dua
perangkat. Refresh token client, expiry session backend, reconnect signaling,
serta command remote melalui signaling sudah diimplementasikan, tetapi tetap
perlu diuji pada environment Android dan jaringan yang relevan. Dokumentasi
atau UI yang menyatakan “menunggu koneksi” tidak boleh dianggap sebagai bukti
bahwa koneksi media sudah aktif.
# Tutorial Deploy LinkDroid Backend ke VPS

Panduan ini untuk menjalankan backend pada VPS menggunakan repository ini.
Backend yang dideploy adalah folder `backend/`, bukan project Android.

Panduan ini mengasumsikan:

- VPS Rocky Linux 8 atau Linux server yang kompatibel.
- Repository dapat di-clone ke `/root/linkdroid-backend`.
- PostgreSQL, Nginx, Node.js/npm, dan PM2 tersedia atau dapat dipasang.
- Domain yang digunakan adalah `103-245-38-142.sslip.io`.
- Nginx meneruskan traffic ke backend pada `127.0.0.1:3000`.
- Server bersifat shared. Jangan mengubah atau menghapus proses aplikasi lain.

Jika nilai domain, folder, port, atau proses di VPS berbeda, ganti nilainya
secara konsisten di semua langkah.

## 1. Arsitektur deployment

Alur production yang diharapkan:

```text
Android APK
    |
    | HTTPS / WSS
    v
Nginx :443
    |
    | reverse proxy
    v
LinkDroid backend :127.0.0.1:3000
    |
    +--> PostgreSQL :127.0.0.1:5432
    +--> coturn :3478 (untuk TURN/STUN bila WebRTC sudah digunakan)
```

Port `3000` dan `5432` tidak perlu dibuka ke internet. Port publik yang
dibutuhkan adalah:

- `80/tcp` untuk redirect HTTP dan validasi/renewal Certbot;
- `443/tcp` untuk API HTTPS dan WebSocket WSS;
- `3478/tcp` dan `3478/udp` jika TURN dipakai.

## 2. Pemeriksaan awal VPS

Jalankan sebagai user dengan hak `sudo`. Jangan menganggap service yang belum
terlihat dari perintah berikut sudah siap.

```bash
hostname
cat /etc/os-release
node --version
npm --version
git --version
psql --version
nginx -v
pm2 --version

sudo ss -ltnp
sudo ss -lunp
sudo systemctl status postgresql --no-pager
sudo systemctl status nginx --no-pager
sudo pm2 list
```

Pastikan port `3000` tidak sedang dipakai aplikasi lain sebelum deployment:

```bash
sudo ss -ltnp | grep ':3000' || true
```

Pada server shared, jangan menjalankan `pm2 delete all`, `pm2 kill`, atau
mengganti konfigurasi Nginx aplikasi lain.

## 3. Siapkan Node.js dan PM2

Project Replit menggunakan Node.js 20 sebagai baseline. Node.js 20 atau versi
LTS yang lebih baru dapat digunakan selama `npm ci` dan build backend berhasil.

Jika Node.js dan npm belum tersedia, pasang Node.js melalui metode standar yang
disetujui untuk server Anda. Setelah itu, pasang PM2 secara global:

```bash
sudo npm install --global pm2
pm2 --version
```

Jangan memasang dependency dengan `npm install` di production jika lockfile
tersedia. Gunakan `npm ci` agar versi mengikuti `package-lock.json`.

## 4. Ambil repository ke folder deployment

Gunakan folder terpisah yang sesuai dengan `backend/ecosystem.config.cjs`.
Konfigurasi PM2 di repository menetapkan:

- `cwd`: `/root/linkdroid-backend`;
- `script`: `backend/dist/server.js`;
- `HOST`: `127.0.0.1`;
- `PORT`: `3000`;
- nama process: `linkdroid-backend`.

Clone repository:

```bash
cd /root
git clone https://github.com/yastar123/remote_device_android_to_android.git linkdroid-backend
cd /root/linkdroid-backend
```

Jika folder sudah ada:

```bash
cd /root/linkdroid-backend
git status --short
git fetch --all --prune
git log -1 --oneline
```

Jangan melanjutkan update jika folder berisi perubahan manual yang belum
disimpan atau jika repository ternyata bukan versi yang ingin dideploy.

## 5. Buat database PostgreSQL khusus

Backend memakai PostgreSQL melalui Prisma. Gunakan database dan user terpisah
dari aplikasi lain pada VPS.

Periksa service:

```bash
sudo systemctl enable --now postgresql
sudo -u postgres psql -c '\l'
```

Untuk instalasi baru, buat user dan database:

```bash
sudo -u postgres createuser --pwprompt linkdroid
sudo -u postgres createdb --owner=linkdroid linkdroid_db
```

Jika user atau database sudah pernah dibuat, jangan menjalankan perintah di atas
secara membabi buta. Periksa dahulu:

```bash
sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='linkdroid'"
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='linkdroid_db'"
```

Uji koneksi memakai password yang sudah dibuat, tanpa menuliskannya ke shell
history:

```bash
read -s -p "Password PostgreSQL linkdroid: " LINKDROID_DB_PASSWORD
echo
PGPASSWORD="$LINKDROID_DB_PASSWORD" psql \
  "postgresql://linkdroid@127.0.0.1:5432/linkdroid_db" \
  -c 'SELECT 1;'
unset LINKDROID_DB_PASSWORD
```

Jika PostgreSQL mewajibkan format connection string tertentu, sesuaikan
`DATABASE_URL` pada langkah berikutnya. Password tidak boleh ditulis ke
repository, command yang tersimpan, atau dokumen ini.

## 6. Buat environment backend

Salin template environment:

```bash
cd /root/linkdroid-backend
cp backend/.env.example backend/.env
chmod 600 backend/.env
```

Edit file dengan editor server:

```bash
vi backend/.env
```

Isi minimal production:

```dotenv
PORT=3000
HOST=127.0.0.1
DATABASE_URL=postgresql://linkdroid:PASSWORD_DATABASE@127.0.0.1:5432/linkdroid_db
JWT_SECRET=GANTI_DENGAN_SECRET_ACAK_MINIMAL_32_KARAKTER
JWT_ISSUER=linkdroid-api
ACCESS_TOKEN_TTL=15m
REFRESH_TOKEN_DAYS=30
CORS_ORIGIN=https://103-245-38-142.sslip.io
ADMIN_INVITE_CODE=GANTI_DENGAN_KODE_INVITE_ADMIN
```

Untuk membuat secret acak tanpa menampilkan nilainya ke dokumen:

```bash
openssl rand -base64 48
```

Simpan nilai yang dihasilkan langsung ke `backend/.env`. Jangan menyalin nilai
secret ke chat, commit, issue, atau log.

### Konfigurasi TURN opsional

Backend hanya mengembalikan credential TURN jika ketiga variable berikut
terisi:

```dotenv
TURN_URLS=turn:103.245.38.142:3478
TURN_USERNAME=ISI_SESUAI_KONFIGURASI_COTURN
TURN_CREDENTIAL=ISI_SESUAI_KONFIGURASI_COTURN
```

Periksa konfigurasi coturn di VPS sebelum mengisi nilai tersebut:

```bash
sudo systemctl status coturn --no-pager
sudo ss -ltnup | grep 3478 || true
sudo grep -E '^(listening-ip|external-ip|realm|user|lt-cred-mech|static-auth-secret|cert|pkey)' \
  /etc/turnserver.conf
```

Jangan menyalin output yang mengandung password atau secret ke repository.
Endpoint TURN di backend adalah fondasi untuk WebRTC; aplikasi Android pada
versi source saat ini belum menjalankan pipeline WebRTC end-to-end.

## 7. Install dependency dan build backend

Dari root repository:

```bash
cd /root/linkdroid-backend
npm ci
```

Generate Prisma Client:

```bash
npm run backend:prisma:generate
```

Sebelum migration pertama, backup database jika database bukan database kosong:

```bash
sudo -u postgres pg_dump \
  --format=custom \
  --file="/root/linkdroid-backend-backup-$(date +%Y%m%d-%H%M%S).dump" \
  linkdroid_db
```

Terapkan migration yang sudah ada di repository:

```bash
npm run backend:prisma:migrate
```

Build TypeScript:

```bash
npm run backend:build
```

Pastikan file hasil build ada:

```bash
test -f backend/dist/server.js
ls -lh backend/dist/server.js
```

## 8. Uji backend tanpa Nginx

Jalankan backend sementara di terminal terpisah:

```bash
cd /root/linkdroid-backend
npm run backend:start
```

Di terminal lain, cek health:

```bash
curl --fail-with-body --silent --show-error \
  http://127.0.0.1:3000/health
echo
```

Respons yang diharapkan memiliki bentuk berikut:

```json
{
  "ok": true,
  "service": "linkdroid-backend",
  "database": "up"
}
```

Hentikan proses sementara dengan `Ctrl+C` setelah health check berhasil. Jika
backend gagal start, periksa `DATABASE_URL`, `JWT_SECRET`, migration, dan
versi Node.js sebelum memakai PM2.

## 9. Jalankan dengan PM2

Konfigurasi PM2 yang sudah ada di repository adalah
`backend/ecosystem.config.cjs`. Dari root repository:

```bash
cd /root/linkdroid-backend
pm2 start backend/ecosystem.config.cjs
pm2 status
pm2 logs linkdroid-backend --lines 100
```

Jika process `linkdroid-backend` sudah ada dan hanya ingin memuat build atau
environment baru:

```bash
cd /root/linkdroid-backend
pm2 restart linkdroid-backend --update-env
pm2 save
```

Jika PM2 melaporkan nama process sudah ada, jangan membuat process kedua.
Gunakan `pm2 describe linkdroid-backend` dan `pm2 restart` setelah memastikan
process tersebut memang milik repository ini.

Aktifkan PM2 saat boot. Jalankan perintah yang dicetak oleh `pm2 startup`
sebagai root sesuai output server:

```bash
pm2 startup
pm2 save
```

Verifikasi bahwa hanya process LinkDroid yang dibuat atau diubah:

```bash
pm2 status
pm2 describe linkdroid-backend
curl --fail-with-body --silent --show-error \
  http://127.0.0.1:3000/health
echo
```

## 10. Konfigurasi Nginx reverse proxy

Jika konfigurasi LinkDroid belum ada, buat file terpisah. Jangan mengedit file
konfigurasi aplikasi lain.

```bash
sudo vi /etc/nginx/conf.d/linkdroid-api.conf
```

Gunakan konfigurasi HTTP sementara berikut sebelum sertifikat diterbitkan:

```nginx
server {
    listen 80;
    server_name 103-245-38-142.sslip.io;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
    }
}
```

Uji dan reload Nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
curl --fail-with-body --silent --show-error \
  http://103-245-38-142.sslip.io/health
echo
```

## 11. Pasang HTTPS dengan Certbot

Pastikan DNS/domain mengarah ke VPS dan port 80 dapat diakses dari internet.
Pasang Certbot sesuai repository package Rocky Linux yang digunakan. Setelah
tersedia:

```bash
sudo certbot --nginx -d 103-245-38-142.sslip.io
```

Pilih redirect HTTP ke HTTPS jika Certbot menawarkannya. Setelah selesai:

```bash
sudo nginx -t
sudo systemctl reload nginx
curl --fail-with-body --silent --show-error \
  https://103-245-38-142.sslip.io/health
echo
sudo certbot renew --dry-run
```

Konfigurasi final Nginx harus meneruskan header WebSocket. Jika Certbot tidak
mempertahankan blok `location`, pastikan file memiliki konfigurasi seperti ini:

```nginx
server {
    listen 443 ssl;
    server_name 103-245-38-142.sslip.io;

    ssl_certificate /etc/letsencrypt/live/103-245-38-142.sslip.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/103-245-38-142.sslip.io/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
    }
}

server {
    listen 80;
    server_name 103-245-38-142.sslip.io;
    return 301 https://$host$request_uri;
}
```

Jangan menyalin blok sertifikat ini sebelum file certificate benar-benar ada.

## 12. Firewall

Jika `firewalld` digunakan, buka hanya port publik yang diperlukan:

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-port=3478/tcp
sudo firewall-cmd --permanent --add-port=3478/udp
sudo firewall-cmd --reload
sudo firewall-cmd --list-all
```

Jangan membuka port `3000` atau `5432` ke publik. Port TURN hanya perlu dibuka
jika TURN memang akan digunakan. Jangan menghapus rule firewall aplikasi lain.

## 13. Uji endpoint production

Health check melalui domain:

```bash
curl -i https://103-245-38-142.sslip.io/health
```

Uji pendaftaran worker:

```bash
curl -i -X POST https://103-245-38-142.sslip.io/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"worker@example.com","password":"GANTI_PASSWORD_MINIMAL_8"}'
```

Untuk mendaftarkan Admin, gunakan invite code yang sama dengan
`ADMIN_INVITE_CODE`:

```bash
curl -i -X POST https://103-245-38-142.sslip.io/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"GANTI_PASSWORD_MINIMAL_8","role":"ADMIN","adminInviteCode":"GANTI_INVITE_CODE"}'
```

Jangan memakai password atau email contoh untuk penggunaan nyata.

Setelah login berhasil, simpan access token hanya di shell sementara:

```bash
read -s -p "Access token: " ACCESS_TOKEN
echo
curl -i https://103-245-38-142.sslip.io/api/v1/me \
  -H "Authorization: Bearer $ACCESS_TOKEN"
unset ACCESS_TOKEN
```

Backend mengharuskan header:

```text
Authorization: Bearer <accessToken>
```

## 14. Update deployment

Sebelum update, buat backup database dan catat versi yang sedang berjalan:

```bash
cd /root/linkdroid-backend
git log -1 --oneline
sudo -u postgres pg_dump \
  --format=custom \
  --file="/root/linkdroid-backend-backup-$(date +%Y%m%d-%H%M%S).dump" \
  linkdroid_db
```

Ambil source terbaru dan build:

```bash
cd /root/linkdroid-backend
git fetch --all --prune
git pull --ff-only
npm ci
npm run backend:prisma:generate
npm run backend:prisma:migrate
npm run backend:build
pm2 restart linkdroid-backend --update-env
pm2 save
```

Verifikasi sesudah restart:

```bash
pm2 status
pm2 logs linkdroid-backend --lines 100
curl --fail-with-body --silent --show-error \
  https://103-245-38-142.sslip.io/health
echo
```

Jalankan migration sebelum restart hanya jika migration tersebut memang
kompatibel dengan versi backend yang akan dijalankan. Untuk perubahan schema
berisiko, siapkan backup dan rencana rollback terlebih dahulu.

## 15. Troubleshooting

### Health check `502 Bad Gateway`

Periksa apakah backend listen:

```bash
sudo ss -ltnp | grep ':3000' || true
pm2 status
pm2 logs linkdroid-backend --lines 200
curl -i http://127.0.0.1:3000/health
sudo tail -n 100 /var/log/nginx/error.log
```

Jika `curl` localhost gagal, masalah ada pada backend, environment, database,
atau PM2. Jika localhost berhasil tetapi domain gagal, periksa Nginx, firewall,
sertifikat, dan routing domain.

### Backend tidak start

```bash
cd /root/linkdroid-backend
node --version
npm run backend:build
pm2 logs linkdroid-backend --lines 200
```

Jangan menampilkan isi `backend/.env` di terminal recording, log, atau chat.
Periksa keberadaan key tanpa mencetak nilainya, misalnya:

```bash
test -s backend/.env && stat -c '%a %n' backend/.env
```

### Health check database `503`

```bash
sudo systemctl status postgresql --no-pager
sudo -u postgres psql -d linkdroid_db -c 'SELECT 1;'
cd /root/linkdroid-backend
npm run backend:prisma:migrate
```

Pastikan `DATABASE_URL` menunjuk ke database dan user yang benar. Jangan
menghapus database untuk memperbaiki error sebelum ada backup dan persetujuan
yang jelas.

### WebSocket tidak tersambung

Pastikan:

- URL client memakai `wss://103-245-38-142.sslip.io/ws`;
- token dan `device_id` dikirim sebagai query parameter;
- device sudah terdaftar pada user yang sama;
- Nginx meneruskan `Upgrade` dan `Connection`;
- session berstatus `APPROVED` atau `ACTIVE`;
- `pm2 logs` tidak menunjukkan error WebSocket;
- timeout Nginx cukup panjang untuk koneksi long-lived.

WebSocket signaling yang hidup belum berarti video atau remote control sudah
berjalan. Source Android saat ini belum memiliki implementasi WebRTC
`PeerConnection` dan data channel.

## 16. Checklist deploy

- [ ] Port `3000` kosong dan tidak digunakan aplikasi lain.
- [ ] Repository berada di `/root/linkdroid-backend`.
- [ ] PostgreSQL service aktif.
- [ ] Database `linkdroid_db` dan user `linkdroid` tersedia.
- [ ] `backend/.env` dibuat dengan permission `600`.
- [ ] `DATABASE_URL` dan `JWT_SECRET` valid.
- [ ] TURN credential hanya diisi jika sudah dikonfirmasi.
- [ ] `npm ci` berhasil.
- [ ] Prisma Client berhasil dibuat.
- [ ] Migration berhasil diterapkan.
- [ ] `npm run backend:build` berhasil.
- [ ] Backend menjawab `http://127.0.0.1:3000/health`.
- [ ] PM2 process `linkdroid-backend` aktif.
- [ ] `pm2 save` berhasil.
- [ ] Nginx `nginx -t` berhasil.
- [ ] HTTPS `/health` mengembalikan status sukses.
- [ ] Header WebSocket sudah diteruskan.
- [ ] Port internal `3000` dan `5432` tidak diekspos.
- [ ] Tidak ada process atau konfigurasi aplikasi shared lain yang diubah.

## 17. Batasan fitur setelah deploy

Deploy backend ini mengaktifkan API auth, device, task, session, dan relay
signaling yang ada di source. Deploy ini tidak otomatis menyediakan:

- video layar WebRTC;
- audio;
- data channel command remote;
- eksekusi tap/swipe dari controller;
- refresh token otomatis pada APK;
- session expiry scheduler;
- push notification;
- release APK signing.

Fitur tersebut masih harus diimplementasikan dan diuji pada dua perangkat
Android secara terpisah.
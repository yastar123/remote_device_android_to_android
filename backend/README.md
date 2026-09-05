# LinkDroid backend

Backend Node.js untuk API akun, pairing device, task data pelanggan, lifecycle
sesi remote, dan WebSocket signaling. Backend ini sengaja berada di folder
monorepo terpisah dari project Android native.

## Menjalankan lokal

1. Salin `backend/.env.example` menjadi `backend/.env`.
2. Isi `DATABASE_URL` dan `JWT_SECRET` tanpa memasukkannya ke Git.
3. Buat database PostgreSQL yang kosong.
4. Jalankan:

```bash
npm run backend:prisma:generate
npm run backend:prisma:migrate
npm run backend:dev
```

Health check:

```text
GET http://127.0.0.1:3000/health
```

## Endpoint utama

Semua endpoint selain auth dan health memakai:

```text
Authorization: Bearer <accessToken>
```

### Auth

- `POST /api/v1/auth/register`
  - Body: `{ "email", "password", "role": "WORKER" }`
  - Role `ADMIN` membutuhkan `adminInviteCode` yang sama dengan
    `ADMIN_INVITE_CODE`.
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/me`

Password minimal 8 karakter. Access token berumur pendek dan refresh token
disimpan sebagai hash di database serta dirotasi setiap kali digunakan.

### Device

- `POST /api/v1/devices/register`
  - Body: `deviceId` 9 digit, `deviceName`, `androidVersion`, `appVersion`.
  - `deviceId` dibuat oleh Android lalu dipairing ke akun yang terautentikasi.
- `GET /api/v1/devices`
- `DELETE /api/v1/devices/:deviceId`

### Monitoring session

- `POST /api/v1/sessions` — Admin membuat request dengan
  `controllerDeviceId` dan `receiverDeviceId`.
- `GET /api/v1/sessions`
- `POST /api/v1/sessions/:id/approve` — hanya akun Petugas penerima.
- `POST /api/v1/sessions/:id/reject` — hanya akun Petugas penerima.
- `POST /api/v1/sessions/:id/end` — salah satu pihak.

### Data pelanggan

- `POST /api/v1/tasks` — Petugas membuat task dengan data pelanggan tervalidasi.
- `GET /api/v1/tasks` — Admin melihat task yang ditugaskan atau belum ditugaskan;
  Petugas melihat task miliknya.
- `PATCH /api/v1/tasks/:id/status` — ubah ke `DATA_INPUT`, `PLN_MOBILE`,
  `IN_REVIEW`, `COMPLETED`, atau `NEEDS_CORRECTION`.

### TURN dan signaling

- `GET /api/v1/turn/credentials` mengembalikan ICE server hanya jika kredensial
  TURN dikonfigurasi pada environment backend.
- WebSocket `wss://103-245-38-142.sslip.io/ws?access_token=...&device_id=...`
  mengirim pesan `session.signal` berisi `offer`, `answer`, atau `ice-candidate`.
  Server hanya merelay signal kepada dua device yang tercatat pada session
  tersebut dan sudah berstatus `APPROVED` atau `ACTIVE`.

## Deploy ke VPS

Backend harus berada di `/root/linkdroid-backend`, menggunakan `.env` server,
dan listen di `127.0.0.1:3000` agar cocok dengan Nginx LinkDroid. Setelah build
dan migration:

```bash
pm2 start backend/ecosystem.config.cjs
pm2 save
```

Jangan mengubah proses PM2 aplikasi lain pada VPS shared.
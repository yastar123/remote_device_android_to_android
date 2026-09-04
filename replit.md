# LinkDroid Android Remote

LinkDroid membantu pengguna menghubungkan dan memberi dukungan jarak jauh antarperangkat Android dengan alur yang jelas dan aman.

## Run & Operate

- `pnpm --filter @workspace/api-server run dev` — run the API server (port 5000)
- `pnpm run typecheck` — full typecheck across all packages
- `pnpm run build` — typecheck + build all packages
- `cd artifacts/linkdroid-android/native-kotlin && gradle assembleDebug` — build the native Android debug APK
- Native APK output: `artifacts/linkdroid-android/native-kotlin/app/build/outputs/apk/debug/app-debug.apk`
- `pnpm --filter @workspace/api-spec run codegen` — regenerate API hooks and Zod schemas from the OpenAPI spec
- `pnpm --filter @workspace/db run push` — push DB schema changes (dev only)
- Required env: `DATABASE_URL` — Postgres connection string

## Stack

- pnpm workspaces, Node.js 24, TypeScript 5.9
- API: Express 5
- DB: PostgreSQL + Drizzle ORM
- Validation: Zod (`zod/v4`), `drizzle-zod`
- API codegen: Orval (from OpenAPI spec)
- Build: esbuild (CJS bundle)

## Where things live

- `artifacts/linkdroid-android/app/index.tsx` — preview interaktif untuk login, beranda, perangkat, pengaturan, dan sesi remote.
- `artifacts/linkdroid-android/constants/colors.ts` — token warna terang dengan aksen biru.
- `artifacts/linkdroid-android/native-kotlin/` — aplikasi Android native Kotlin + Jetpack Compose, MediaProjection, Accessibility Service, dan foreground service.
- `artifacts/linkdroid-android/assets/images/linkdroid-icon.png` — ikon aplikasi LinkDroid.

## Architecture decisions

- Preview mobile menggunakan Expo agar alur dan tampilan dapat dicoba lintas perangkat melalui preview.
- Output Android utama menggunakan aplikasi native Kotlin/Jetpack Compose karena screen capture dan kontrol sentuhan membutuhkan API Android khusus.
- Akses remote harus selalu melalui persetujuan perangkat penerima; Accessibility Service tidak boleh diaktifkan diam-diam.
- Penyimpanan lokal preview menggunakan AsyncStorage untuk sesi login dan daftar perangkat.

## Product

- Login dan demo lokal.
- ID perangkat pribadi dengan status online dan aksi berbagi.
- Koneksi berdasarkan ID perangkat tujuan.
- Daftar perangkat tersimpan, tambah perangkat, dan status online/offline.
- Sesi remote visual dengan kontrol dasar, status koneksi, audio, layar, dan akhiri sesi.
- Pengaturan izin perangkat dan keluar akun.

## User preferences

- Tampilan minimalis, clean, modern, dan elegan.
- Layar pertama adalah form login.
- Nuansa terang bersih dengan aksen biru solid.
- Target aplikasi: remote antarperangkat Android, dibuat dengan teknologi Kotlin untuk fondasi native.

## Gotchas

- Kontrol Android sungguhan memerlukan MediaProjection, Accessibility Service, transport signaling/WebRTC, dan persetujuan eksplisit dari perangkat penerima.
- Preview dapat diuji lewat workflow Expo; build native Kotlin memerlukan Android SDK/Gradle.

## Pointers

- See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details

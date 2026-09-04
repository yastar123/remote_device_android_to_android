# LinkDroid Android

Repository ini khusus untuk aplikasi Android native berbasis Kotlin dan
Jetpack Compose.

Tidak ada website, aplikasi Expo, server Node.js, atau workspace JavaScript
dalam repository ini. Kemampuan Android seperti MediaProjection dan
Accessibility Service diimplementasikan langsung melalui Kotlin dan Android
SDK.

## Build APK debug

```bash
cd artifacts/linkdroid-android/native-kotlin
gradle assembleDebug
```

APK hasil build tersedia di:

```text
artifacts/linkdroid-android/native-kotlin/app/build/outputs/apk/debug/app-debug.apk
```

Buka folder `artifacts/linkdroid-android/native-kotlin` di Android Studio untuk
menjalankan aplikasi pada emulator atau perangkat Android.
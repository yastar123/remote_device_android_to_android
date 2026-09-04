---
name: LinkDroid native Android direction
description: The project is intentionally maintained as a Kotlin-only Android application.
---

The LinkDroid repository is intentionally Kotlin-only. The Android app lives in the native Gradle project and should not regain an Expo, website, or Node workspace layer.

**Why:** The product depends on Android-only capabilities such as MediaProjection and Accessibility Service, and the user explicitly wants the repository to build an Android APK rather than a browser preview.

**How to apply:** Keep UI, permission handling, capture, session state, and future signaling in Kotlin/Gradle. Do not add JavaScript preview artifacts as an alternative implementation.
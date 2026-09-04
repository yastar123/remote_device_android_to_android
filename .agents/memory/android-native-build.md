---
name: Native Android build environment
description: Constraints and setup needed to build the native Kotlin APK in this workspace.
---

Native Kotlin builds need a local Android SDK in addition to Java and Gradle; the Replit package index may not expose the Nix androidenv SDK attribute.

**Why:** The project can compile successfully with Gradle once Android platform 35 and build-tools are available, but a Java-only environment fails before Kotlin compilation.

**How to apply:** Check `ANDROID_HOME` and `local.properties` before diagnosing native build errors; keep SDK installation outside the repository and keep `local.properties` ignored.
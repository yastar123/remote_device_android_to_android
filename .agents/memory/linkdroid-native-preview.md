---
name: LinkDroid native and preview split
description: Why LinkDroid keeps a runnable mobile preview alongside native Android foundations.
---

The LinkDroid product uses an Expo mobile preview for fast visual and flow validation, while the production Android path lives in the native-kotlin foundation.

**Why:** The Replit mobile artifact preview is available through Expo, but real Android remote control requires MediaProjection, Accessibility Service, and a native build.

**How to apply:** Keep preview interactions honest and permission-aware; implement screen streaming, authenticated signaling, session consent, and remote input transport in the native Kotlin path before calling the remote feature production-ready.
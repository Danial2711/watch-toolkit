# RF Toolkit (Wear OS, Galaxy Watch4 Classic SM-R890)

Full context, device capabilities, status, pitfalls and backlog: **docs/CONTEXT.md**. Read it before planning work.

Essentials:
- Build/test: `./gradlew testDebugUnitTest`, `./gradlew installDebug` (watch over wireless ADB only).
- Put logic in pure Kotlin with JVM unit tests, and keep Android adapters thin. Screen-scoped features start and stop with their screen.
- Never turn the watch screen off over ADB (it kills wireless debugging). Keep the watch on its charger while developing.
- No external hardware. NFC = own NDEF tag only. Port scans = private IPs only. Radio features detect, never jam.
- Exports are in the app's `files/export`; pull with `tools/pull.sh` and analyse with `tools/survey.py`.

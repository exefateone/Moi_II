# Android Studio project — ИИ Паук v0.39.3

Source scaffold; build/runtime evidence is pending.

## v0.38 added sources
- `SpiderOverlayService`: user-enabled floating overlay, draggable bubble and shortcuts for app modules.
- `MainActivity`: explicit overlay permission request, bridge methods `enable_overlay`, `disable_overlay`, `save_html`, and safe HTML save via Android document picker.
- HTML Code Studio: isolated preview/export; APK Build Runner not implemented.
- Spider Media now includes local/document-provider playback, direct HTTPS URLs, HLS/DASH dependencies, playlist queue and a MediaSessionService source. Playback and background behavior still need Android build/device verification.

## Build instructions
Use Gradle 8.11.1, JDK 17 and Android SDK Platform 35. A GitHub Actions workflow at `../.github/workflows/android-build.yml` provisions these tools in CI, runs unit tests, assembles the debug APK, and uploads the resulting artifact. This workflow still needs to be executed in a connected GitHub repository. For local builds, Sync Gradle dependencies, run `testDebugUnitTest`, `assembleDebug`, install the debug APK and test on device. Inspect `docs/GRADLE_COMPATIBILITY.md` if there are build errors.

`SYSTEM_ALERT_WINDOW` requires explicit user consent via Android settings. `specialUse` foreground-service declaration must also be reviewed against the current Android/Play policy before publishing. Do not put signing keys or provider secrets in this archive.

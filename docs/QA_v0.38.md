# QA report — ИИ Паук v0.38 Widget + Code Studio

Date: 2026-10-12.

## Passed in this runtime
- Python contract/runtime tests: **34 passed**.
- HTML IDs: **128 unique**, no duplicate IDs.
- Inline JavaScript syntax (`node --check`): passed.
- Packaged Android asset equals the current `app-web/ii-pauk.html`: passed.
- Chromium UI smoke: page load, existing workspace/browser/media/terminal paths, no uncaught page errors: passed.
- Separate UI check: Code Studio opens, HTML preview updates status, APK button honestly reports runner pending; Widget settings explain Android overlay permission: passed.
- Android manifest parses as XML: passed.
- Linux runtime validation: correctly reports `not_built`; this is an expected blocker, not a runtime pass.

## Not run / not verified
- No Gradle executable, Android SDK or adb are available in this runtime.
- No `testDebugUnitTest`, Kotlin compile or `assembleDebug` completed.
- No APK has been produced.
- No physical/emulator device available to test overlay permission, foreground notification, overlay persistence, drag/tap actions, file save picker or media playback.
- `SYSTEM_ALERT_WINDOW` + `specialUse` foreground-service declarations must be checked by the actual Android build and reviewed against distribution policies before a public release.

## Known next acceptance gates
1. Android Studio/CI: Gradle Sync, `testDebugUnitTest`, `assembleDebug`.
2. Install APK: enable overlay permission; verify bubble above a normal third-party app; drag/reposition; open chat/browser/media/terminal/code; hide and re-enable.
3. Save a UTF-8 HTML file through Android Storage Access Framework and reopen it.
4. Re-run browser/media/device tests and archive the logs.

## Repeat verification run (2026-10-12)
- Re-ran `bash scripts/verify_project.sh` from the extracted delivery archive.
- Result: **34/34 Python tests passed**; HTML and packaged-asset sync passed; Chromium UI smoke passed with no uncaught page errors.
- Environment capability check: `gradle`, `adb`, and a project `gradlew` wrapper are absent. Java exists, but Android build cannot be started from this runtime without the Android toolchain.
- Current gate remains **Android baseline build pending**. No code/runtime claims were upgraded based on static/browser tests alone.

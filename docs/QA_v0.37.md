# QA report — ИИ Паук v0.37 Agent + Media Foundation

Date: 2026-10-12

## Checks that passed in this environment

- `./scripts/verify_project.sh`: PASS.
- Python contract/runtime-validator suite: **32 tests passed**.
- HTML shell: **117 unique IDs**, inline JavaScript syntax check passed.
- Packaged Android HTML asset is byte-identical to canonical `app-web/ii-pauk.html`.
- Chromium UI smoke: page loads; workspace create/duplicate/rename/switch/delete/limit works; browser address/search/bookmark/tabs work; Media page truthfully explains that native playback needs Android; terminal remains explicit that it is not a shell; no uncaught page errors.
- Standalone Kotlin compiler smoke for `SpiderAgentModels.kt` + `SpiderPolicyEngine.kt`: PASS for local read, network gate, request-specific approval, critical delete approval and malformed request denial.
- Linux runtime release gate returns the expected NOT READY result because the bundled manifest is deliberately `not_built`.

## Checks not performed

- Gradle Sync and Android `assembleDebug`.
- Android JUnit `testDebugUnitTest` (the test source exists but Gradle/Android build tools are not available here).
- Installation, physical-device/emulator UI and native bridge tests.
- Actual MP3/video playback, codec edge cases, background notification, lock-screen/headset control, process lifecycle or URI-grant recovery.
- Integration of policy -> user approval UI -> executor -> post-action verification -> audit log.
- PTY, installed Linux userland, connected LLM/provider or release APK.

## Release assessment

**Source foundation is updated; Android release is not ready.** The new Media3/Kotlin source has not been compiled. The policy model/engine has been exercised with a standalone Kotlin smoke harness, but it is not wired to real tool execution. Do not describe this ZIP as a tested APK or a fully autonomous agent.

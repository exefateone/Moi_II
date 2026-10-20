# Порядок реализации ИИ Паук v0.38 → release

> Исторический release/acceptance plan. Текущий приоритет разработки Этапа C изменён по согласованию с пользователем; рабочий порядок C1–C7 и актуальные ограничения описаны в `STAGE_C_EXECUTION_ORDER.md`. Build/device checks продолжаются параллельно по мере доступности среды.

## Сейчас: v0.38 — Widget + Code Studio source
- [x] Native `SpiderOverlayService` source: draggable always-on-top bubble, quick menu, status hook and foreground notification.
- [x] Explicit Android overlay permission flow.
- [x] Widget shortcuts to Chat, Browser, Media, Terminal, Agent and Code Studio.
- [x] Code Studio HTML editor, isolated preview, `.html` export and an honest non-connected APK state.
- [x] Static/HTML/JS/Chromium smoke tests pass in this environment.
- [ ] Android Gradle compile and unit tests.
- [ ] Install and test widget, Media3 and HTML save on a real Android device.

## Gate 1 — finish the Android baseline (must pass first)
1. Open `android-project/` in Android Studio with JDK 17 and Android SDK Platform 35 (or the project's validated equivalent).
2. Run Gradle Sync, `testDebugUnitTest` and `assembleDebug`; fix errors against real build logs.
3. Install the debug APK to a real test phone.
4. Verify overlay permission, visibility over normal third-party apps, move/tap/hide/restart, notification, browser/TLS, Media3 audio/video, and HTML save using Android's document picker.
5. Save build and device logs. Until these pass, Android runtime readiness remains 0% verified.

## v0.39 — Code → HTML Demo / APK Build Runner
- [x] HTML demo path at source level: edit → sandboxed preview → save/download `.html`.
- [ ] Define build request format and input project contract.
- [ ] Implement isolated build worker with pinned JDK/Gradle/Android SDK image.
- [ ] Restrict source upload, build duration, memory, disk, network and artefact retention.
- [ ] Run tests and collect full logs, reports and APK output checksum.
- [ ] Keep signing keys under the user's control; no secret key bundled in app or logs.
- [ ] Only show “APK ready” when a real artifact exists and its build/test evidence is recorded.

## v0.40 — Real PTY Terminal + Workspace
- [ ] Integrate a selected PTY/terminal implementation and verify license/version.
- [ ] Test prompt, `pwd`, `echo`, stdout/stderr, Ctrl-C, cancellation and session reopen.
- [ ] Connect the canonical app-private project directory as cwd and enforce path containment.
- [ ] Test rotation, background/resume and process-death behavior.
- [ ] Do not expose generic shell execution to arbitrary HTML/browser content.

## v0.41 — Linux userland
- [ ] Decide target ABI, userland, licensing and artifact provenance.
- [ ] Build a real bundle; validate against traversal, link escape, special files and decompression limits.
- [ ] Add scoped installation, atomic update and rollback.
- [ ] Test packages, filesystem, command execution, network and app lifecycle on device.

## v0.42 — Agent Executor + Task Manager + widget status
- [x] Task coordinator/state machine source foundation and schema-v1 durable repository wired into `SpiderAgentRuntime` (Android build still unverified).
- [ ] Typed tool router/executor only; no free-form unrestricted tool invocation.
- [x] Current registered workspace-list tool goes through Sentinel policy broker and pre-execution audit.
- [ ] Wire every future tool through request-bound approval where required.
- [x] Narrow outcome verifier, audit log, one-time request replay ledger and interrupted-task recovery source.
- [ ] Independent filesystem inventory evidence, bounded retry budget and cancellation.
- [ ] Feed real task events and state from Task Manager to overlay badge/quick menu; no fake status strings.

## v0.43 — Spider Code autonomous loop
- [ ] Inspect → plan → edit → run tests → preview HTML or build APK → verify artifact → report.
- [ ] Record changed files, commands, test results, warnings and artefact references.
- [x] Recover from interruption without automatic replay (source-level policy and pure Kotlin/JVM smoke tested).

## v0.44+ — memory, models, server and advanced media
- [ ] Project-scoped memory with view/edit/delete/export/forget.
- [x] Configurable Yandex/OpenAI-compatible provider adapter, server-only keys, bounded timeouts, redacted errors, and process-local per-route rate/concurrency limits (source/mocked tests).
- [ ] Production gateway-wide/user-specific quotas, spend budgets, monitoring and live-provider/device end-to-end verification.
- [ ] Optional remote isolated Linux worker for long-running tasks.
- [ ] Media metadata, conversion/extraction via vetted FFmpeg build, speech recognition and media analysis.
- [ ] Additional connectors and integrations, enabled per permission.

## Completion rule
A feature counts as implemented only after source tests, Android compilation, behavioral tests, and device evidence appropriate to the feature. HTML-only test success does not prove native Android behavior. No APK, true Linux environment, full autonomous agent or global overlay should be described as fully operational until those gates pass.

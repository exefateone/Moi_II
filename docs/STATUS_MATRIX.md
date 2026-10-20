# Status matrix — v0.39.1 Bounded Static Project Check

## Active source checkpoint — v0.39.1 (18 October 2026)

The Agent Broker has five typed workspace tools: inventory list, bounded content search, approved read, approved atomic write, and `CHECK_WORKSPACE_PROJECT`. The new project check is read-only and limited to JSON syntax, secure XML/SVG parse and UTF-8 readability within fixed scan bounds. It never runs project code, tests, shell or builds. Python 111/111, Agent Core JVM 15 grouped scenarios, Chromium 8/8, OpenCode Zen/source connector smokes, local LLM Kotlin source type-check via signature stubs, Python compilation and shell syntax passed in this checkpoint. The SQLite task repository type-check timed out and is not considered passed. Full Android Gradle/APK, emulator/device and live-provider checks are still unverified.


| Component | Source readiness | Android verified | Remaining gate |
|---|---:|---:|---|
| HTML shell / menus | 85% | 0% in this container | static/UI smoke + packaged APK |
| In-app Spider widget | 80% | 0% | device lifecycle / input tests |
| Cross-app overlay widget | 55% | 0% | build + permission + drag/action + OEM/device test |
| Browser | 70% | 0% current archive | build + network/TLS/download/upload device QA |
| Media3 audio/video | 65% source | 0% | compile + local, HTTPS, HLS/DASH, playlist, background-session/device tests |
| Code Studio preview | 75% | 0% Android | regression tests and device preview isolation |
| Save HTML (SAF) | 55% | 0% Android | file picker, UTF-8, long file test |
| APK Build Runner | 10% | 0% | isolated Gradle/SDK worker, logs, output, signing ownership |
| Agent contracts/policy | 75% source foundation | 0% Android end-to-end | additional typed handlers, build/device proof |
| Workspace project check | Implemented as bounded static source tool | JVM harness passed | Android runtime; broad-language lint/test runner remains separate and unimplemented |
| Task coordinator / widget task status | 75% source foundation | 0% | SQLite history needs Android verification; shared widget task-state events and more handlers remain |
| Terminal PTY | 15% | 0% | real PTY + process/lifecycle tests |
| Linux runtime | 5% | 0% | real ABI-specific userland bundle and release-gate proof |
| Project memory / self-learning | 70% source | 0% | SQLite Android instrumentation/device QA, scaling profile, measured retrieval evals; retrieval adapts behavior but does not train model weights |
| Local LLM + Yandex AI Studio | 55% source | 0% | native CMake/Gradle build, real GGUF inference on device, configured Yandex backend and live E2E; in-process provider rate/concurrency guards added; distributed gateway quotas and live E2E still needed |
| Signed release APK | 10% | 0% | build, QA, signing configuration and release evidence |

**Overall:** source/architecture foundation approximately 45%; production readiness approximately 15–20%. These are engineering estimates, not measured test coverage. A component marked 0% Android verified does not mean no source exists—it means no build/device proof was available.


## Stage C1 checkpoint — 15 October 2026

`SpiderTaskCoordinator.kt` and `SpiderToolBroker.kt` are now present as source and were compiled as pure Kotlin source with `kotlinc`. Their initial behavior harness passed 19 assertions. The expanded `test_agent_core_jvm.sh` now passes 5 grouped smoke scenarios, while new JUnit test source files cover coordinator, broker, persistence and workspace-handler invariants; Gradle has not yet compiled/executed these JUnit tests. A versioned `FileSpiderTaskRepository` is wired into the `SpiderAgentRuntime`; one read-only workspace-list handler is registered behind policy/audit. A pure Kotlin/JVM smoke harness compiles and tests these platform-independent components. The runtime/UI behavior has not been verified by Gradle or on Android; no real model provider is connected. These source readiness percentages remain estimates and do not imply Android runtime validation.


## Stage C1 provider planner checkpoint — 15 October 2026

Added `HttpSpiderTaskPlanner`, Android Keystore-backed client-token storage, native `SpiderAgentActivity`, and a WebView bridge shortcut. Added the authenticated FastAPI `/api/agent/plan` route with an allowlist containing only `LIST_WORKSPACE_FILES`, 16 KiB request-body limit, bounded output, provider timeout, HTTPS provider URL validation and redacted errors. Backend behavior is tested against a mocked provider; this does not count as a live model call. Android app build/device verification remains 0% until Gradle/SDK and device acceptance run. Percentage figures are rough source-readiness estimates, not measured coverage.


## Stage C1 workspace approval/verifier checkpoint — 15 October 2026

The native task screen now selects or creates an app-private workspace and presents a visible one-time confirmation before dispatching the currently supported `LIST_WORKSPACE_FILES` handler. The same request ID created for the confirmation is passed to the broker only after approval. A `WorkspaceListingResultVerifier` fails closed unless tool execution and final audit succeeded, the summary count matches compact versioned evidence, and a fresh NIO walk independently reproduces the bounded inventory SHA-256 digest. Symlinks and internal workspace metadata are excluded; truncated inventories cannot be marked complete. The current Python suite is 56/56 and the pure Kotlin/JVM Agent Core smoke covers 11 grouped scenarios. Android Gradle build, live provider request and device behavior remain unverified.


## Stage C1 durable replay and restart recovery checkpoint — 15 October 2026

Added `FileSpiderRequestLedger`, which stores SHA-256 digests of consumed one-time request IDs in an app-private versioned file, writes atomically, and fails closed on storage/corruption rather than silently resetting replay protection. `SpiderToolBroker` claims the ID before pre-execution audit and handler dispatch, and the Android runtime uses a durable ledger file. Added `recoverInterruptedTasks()` and wired startup recovery so planning/execution/approval/verification states interrupted by a process restart become explicitly failed; a running step records an unknown outcome and is never automatically replayed, while approval-waiting steps are skipped. Pure Kotlin/JVM smoke now covers 10 grouped scenarios, including cross-broker replay, corrupt ledger preservation and crash recovery. Python/static tests are re-run for this checkpoint. Android Gradle/device and live model-provider tests remain unverified.


## Stage C — Spider Media source milestone — 15 October 2026

The player source now has multi-file SAF selection, a URL field for direct HTTPS media, playlist management, previous/next, queue preview, HLS and DASH Media3 modules, URL policy tests and a MediaSessionService configured for audio focus/noisy-route handling. The JVM URL-policy smoke script runs without Android dependencies. These checks do not prove Android app compilation or successful streaming on a phone; `mediaPlayerVerified` and `backgroundPlaybackVerified` remain false until Gradle/device QA.


## Stage C1 task history/progress UI — 16 October 2026

- Source state: implemented in `SpiderAgentActivity` and `SpiderAgentRuntime`.
- Visible history: latest 30 persisted tasks, status, count of successful steps, timestamps, shortened task ID and tap-to-open details.
- Recovery: displays startup recovery notice and never restarts interrupted work automatically.
- Audit view: displays short event reference plus timestamp/tool/disposition/result code only; bounded file scan is capped at 512 KiB and 4096 lines. Raw audit summary and request ID are not surfaced.
- Privacy: history display redacts known secret patterns, bearer/assignment values, URL-like inputs and absolute paths from goals/results.
- Verified: Python contract suite 57/57; pure Kotlin/JVM Agent Core smoke 12 grouped scenarios; Media URL smoke 15 assertions; ad-hoc task Activity Kotlin syntax/type check against minimal Android API stubs.
- Not yet verified: Android Gradle compile/unit tests, emulator/device behavior, live provider planning request. No APK built in this checkpoint.


## Stage C1 self-learning checkpoint — 16 October 2026

Added persistent episodic learning memory with verified outcomes, bounded user feedback, safe typed hints to the backend planner, recency-weighted topical retrieval, workspace-scoped memory and explicit global-scope reuse. At that historical checkpoint, the task-history and learning-memory binary repositories had versioned schema migration; task history has since moved to SQLite in the later checkpoint below, while learning memory and skills remain separate stores. Legacy unscoped learning entries load as global. Project A experience is not returned when planning in project B. The UI shows/forgets individual entries and can clear the memory store without deleting task history. Learning still means memory-based adaptation, not updating base-model weights. Current local results: Python suite 61/61; Kotlin/JVM Agent Core 13 grouped smoke scenarios; Spider Media URL-policy smoke 15 assertions; Chromium UI smoke 8 scenarios; 129 unique HTML IDs and packaged asset byte-sync. APK, Gradle/Android JUnit, live provider request and real-device behavior remain unverified.


## Stage C1 SQLite memory checkpoint — 16 October 2026

The runtime has switched the active episodic experience store to `SqliteSpiderLearningMemory` and the separate search/vector store to `SqliteSpiderEmbeddingIndex`. The active SQLite stores have no app-level 64 MiB byte ceiling and do not evict by episode count. Main-store outbox rows make inverted-index updates replayable, and both stores support bounded/page or top-K APIs. Legacy binary stores remain import inputs and are archived on success. Python SQLite DDL/query tests pass; Kotlin SQLite sources type-check against temporary minimal Android API stubs. Android instrumentation tests for 540 imported experiences and 1,100 vectors are added but not run. Gradle/APK/device verification remains 0%; LLM integration is intentionally not part of this checkpoint.


## Stage C1 memory diagnostics checkpoint — 16 October 2026

- Added a user-triggered diagnostics action for SQLite episodic memory and the separate search index. It reports episode/topic/posting/vector/model counts, index outbox backlog, primary/index file sizes including WAL/SHM, volume free space, schema versions and status-only quick checks. No private path, prompt, topic-token list, vector contents or raw database error is returned.
- Verification: Python tests 74/74; HTML 129 unique IDs and sync pass; Chromium smoke 8/8 without uncaught page errors; Python compileall pass.
- Android instrumentation source includes database count/size/integrity assertions, but has not run; no Gradle build/APK/device validation. Full Kotlin Agent Core aggregate suite timed out in this environment, so it is not counted as passing here. LLM is not connected.


## Stage C1 SQLite task-history checkpoint — 16 October 2026

- Active task storage: `SqliteSpiderTaskRepository` in `SpiderAgentRuntime`; the binary `FileSpiderTaskRepository` remains the legacy import decoder.
- Data safety: transactional one-time import, read-back comparison of task and ordered step state, import marker, then archive to `.migrated-sqlite-v1`. Unknown old schemas remain untouched; no destructive SQLite recovery is introduced.
- Scale: history UI uses SQL `COUNT(*)` and the newest 30 rows; startup recovery fetches only active/paused-interrupted candidates rather than every old task.
- Added instrumentation source for migration/reopen, 120-task recent query, unknown legacy schema preservation and future SQLite schema handling. GitHub Actions now includes API 35 emulator instrumentation before debug APK packaging.
- Local verified: Python contract/SQLite suite **79/79**; targeted Kotlin type-check against signature-only Android stubs **PASS**.
- Still not verified: Gradle compilation, official unit test task, connected instrumentation, APK output, emulator/device behavior, low-storage recovery or live model-provider calls. The full Agent Core JVM aggregate was attempted but timed out and is not counted as a pass.


## Latest Stage C1 task-history checkpoint — 16 October 2026

Active runtime task persistence now uses `SqliteSpiderTaskRepository` with task and ordered-step tables. Legacy binary import stores a `pending` marker with the imported rows, verifies database integrity and the reopened values from a separate read-only connection, then commits the final marker and archives the source. Added an instrumentation test for recovery from a simulated process death while the marker is pending. Python source/SQLite contract tests total 79/79 passing; targeted Kotlin type-check passes with signature-only Android stubs. The full Agent Core JVM smoke timed out and is not counted. Gradle, Android instrumentation, APK and device validation remain unverified; the next gate is to run the updated API 35 emulator workflow and inspect its reports/artifact.


## Stage C1 local-first LLM checkpoint — 16 October 2026

The native source now contains llama.cpp v0.6.0 CMake/JNI inference for user-supplied GGUF, a Local LLM screen, three explicit routes (local only, Yandex cloud, local then Yandex), a shared strict parser for both local and cloud plans, and an authenticated `/api/chat` endpoint. Backend config supports Yandex API-key/folder/model settings with a fixed host and generic OpenAI-compatible mode. This is source status only: native compilation, Android unit/instrumentation tests, device generation, live Yandex calls and APK are still unverified. The source availability indicator must remain false until JNI is built and the GGUF model is actually loaded.


## C1 local-engine and Yandex adapter follow-up — 16 October 2026

Latest independently run source checks: **86/86 Python/contract tests**, 130 unique HTML IDs with inline JS syntax passing, Android HTML asset byte-sync, **8/8 Chromium smoke scenarios**, **13/13 Agent Core JVM smoke scenarios** and **15/15 Spider Media URL-policy assertions**. The aggregate `verify_project.sh` ran past the environment execution limit while repeating JVM compilation, so it is not reported as a single full-suite pass; its component checks passed separately.

The local GGUF/JNI and Yandex AI Studio source paths are wired, optional Yandex embedding URIs now accept official nested model names, and CI explicitly installs pinned NDK/CMake. A new Android instrumentation test checks JNI library availability on the emulator. Still at **0% verified runtime** until the GitHub workflow actually builds CMake/Gradle, runs emulator instrumentation, creates an APK, and a real GGUF + live Yandex request are independently accepted. Source contracts use mocked provider responses and test doubles; they do not prove device inference or live service connectivity.

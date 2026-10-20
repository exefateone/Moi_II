# ИИ Паук — Spider Native · v0.39.3

Incremental Android/HTML source package. This is **not a compiled APK** and is not a production release.

## Current checkpoint — v0.39.3 (19 October 2026)
- Removed the OpenCode Server project connector from Android source, manifest registration, Web UI, connector-specific tests, scripts and current integration documentation. OpenCode Zen remains available strictly as a hosted language-model API; project actions use the app-local Agent Broker.
- Fixed a discovered Web UI/backend contract break: cloud chat now uses the authenticated `/api/chat` route (not the deliberately disabled `/api/agent/step` route), sends the bearer token, sends schema-valid bounded fields only, and checks token/configuration with `/api/client/check`.
- Added explicit HTTP(S) CORS origin allow-list via `SPIDER_CORS_ORIGINS`; no wildcard is accepted. Client token is kept in session memory only, not persisted.
- Updated Android Gradle Plugin to 8.10.2 to align the Kotlin 2.2 toolchain with Android compiler compatibility guidance; a real Gradle build is still required to confirm it.
- Added an expanded audit report at `docs/DEEP_AUDIT_AND_REMOVAL_REPORT_2026-10-19.md`.
- Removed the backend-only `PROVIDER_KIND=opencode` alias. Generic OpenAI-compatible provider endpoints require HTTPS; HTTP is rejected, including loopback URLs.
- Aligned current release markers to `0.39.3`, including the HTML shell, Android `versionName`, backend health version and CI artifact names.
- Updated the aggregate source verification script to stop calling the deleted connector smoke test. Corrected tests to assert the current release version and the actual provider-validation error codes.
- Current checks executed in this audit: Python unit/contract tests 119/119; HTML JavaScript syntax and asset-sync checks passed (132 unique IDs); Chromium UI scenarios 8/8 with no uncaught page errors; Agent Core JVM smoke 15 grouped scenarios passed when run independently; Media URL policy 15 assertions passed; OpenCode Zen source smoke; local-LLM Kotlin source type-check; SQLite task repository type-check; Python compilation and shell syntax checks. The aggregate `verify_project.sh` run timed out during its Kotlin phase, so it is not recorded as an aggregate pass; focused component commands were run independently.
- **Not verified:** Android Gradle/NDK build, APK creation/install, device integration, live Yandex/OpenCode Zen provider calls. The current execution environment has no Android SDK, Gradle or `adb`.


## Latest source checkpoint — bounded static project checks (18 Oct 2026)
- Added `CHECK_WORKSPACE_PROJECT` end-to-end as a real Agent Broker tool available to local GGUF, Yandex backend planner and OpenCode Zen planner. It is native Android source, requires its own one-time approval, is audit logged without source content, and is independently repeated against opaque evidence.
- It scans at most 2,000 entries / 500 supported text files / 8 MiB read content with a 64 KiB per-file limit, skips symlinks, secret-looking paths and credential-looking content, checks strict JSON syntax and securely parses XML/SVG without external entities, and validates UTF-8 readability for remaining supported text files. It never executes scripts, project code, shell, build tools or tests.
- The internal skill library now records this checker only after independent verification and only when learning is enabled. Five typed workspace tools are now exposed in the planner allowlist; `RUN_PROJECT_TESTS` remains unimplemented.
- Verification status for this continuation is documented in `docs/CHANGELOG_C1_STATIC_PROJECT_CHECK.md`. No Android APK build or device validation is claimed.

## Prior checkpoint — AI-proposed single-file edits (18 Oct 2026)
- Added a model-proposed edit flow for small text files inside the selected app-private workspace. The local GGUF, Yandex backend, and OpenCode Zen modes use both the overall task goal and the concrete plan-step title to propose the full replacement text; the provider never receives filesystem access.
- The app checks the source path/content, blocks files that look like they contain secrets, and in cloud modes requires explicit consent before sending the task goal/step, path and source text to the configured provider. Source size is capped at 1,900 characters for this first iteration.
- A complete line-diff preview is shown before any write; if it exceeds 60,000 characters, the app refuses approval instead of showing a truncated preview. Existing files require a separate overwrite checkbox. The write is atomic and protected by a SHA-256 pre-image guard (or a `MISSING` sentinel for a new file); the saved version is then independently read back and verified. If the source changes after preview, the write is refused.
- The edit endpoint `POST /api/agent/edit` returns a candidate only. The model does not write files or run tests. Multi-file patching, a project-code execution runner, automatic code execution and APK assembly remain unimplemented. The bounded static JSON/XML checker was added in the 0.39.1 continuation.
- Verification in this checkpoint: Python suite 111/111, Python compilation, shell syntax and 8 Chromium UI smoke scenarios passed. Agent Core JVM harness: 15 grouped scenarios passed, including edit parsing, stale-source prevention, overwrite consent, path safety and independent verification. Android Gradle/NDK compilation, APK, device behavior, and live provider calls are not verified. See `docs/CHANGELOG_C1_AI_EDIT_PROPOSAL.md` and `docs/AI_EDIT_PROPOSAL_REPORT_2026-10-18.md`.

## Latest continuation — terminal PTY and local model bootstrap (17 Oct 2026)
- Replaced the native terminal placeholder with `TerminalView` / `TerminalSession` from the published Termux terminal libraries (version 0.118.0) and connected the trusted WebView bridge plus floating-widget shortcut. The PTY launches Android `/system/bin/sh` as the normal app UID, starts in app-private storage, includes keyboard shortcuts/copy/paste and process cleanup. This is not a full Linux distribution; runtime/device behavior remains unverified until Android Gradle/CMake build and device smoke tests.
- Added optional, explicit `scripts/download_qwen_smoke_model.sh` for Qwen2.5-0.5B-Instruct GGUF Q4_K_M (about 491 MB); it verifies a pinned SHA-256 before placing the model in ignored `llm/models/`. Copy it to the phone and import through the Local LLM screen. It is a small smoke-test model, not the final-quality local assistant. No model weights are auto-downloaded or bundled.
- Third-party terminal-library and optional model notices are documented in `docs/TERMINAL_AND_MODEL_DEPENDENCIES.md`, `docs/THIRD_PARTY_NOTICES.md` and the included `docs/THIRD_PARTY_APACHE_2.0.txt`.

## Added in v0.38
- Android overlay service source: draggable Spider bubble, quick actions to Chat/Browser/Media/Terminal/Agent/Code Studio and persistent foreground notification.
- Explicit Android `SYSTEM_ALERT_WINDOW` permission flow. The user must approve the overlay in system settings.
- Spider Code & Build HTML editor, sandboxed in-app HTML demo, HTML file export (Android SAF bridge or browser Blob download).
- APK build panel that clearly reports the runner is not connected; no fake APK success.
- Full target architecture and estimated readiness percentages in `docs/ARCHITECTURE_v0.38.md`.

## Important status
- `SpiderOverlayService.kt`, new bridge flows and HTML Code Studio are added as source but **have not been compiled or tested on a real device**.
- The floating widget is intended to remain above ordinary app windows only while Android has granted overlay permission and the service remains alive. Secure system surfaces / OS policies may cover it.
- The widget itself does not inspect or control other apps; accessibility-based cross-app automation is a separate consent/security milestone.
- Real PTY, Linux userland, broad autonomous-agent tool coverage, a live-configured provider service and APK Build Runner remain incomplete. Local `llama.cpp`/GGUF inference, local-first planner routing and Yandex AI Studio backend support are now added as source, but native compilation, live credentials/provider calls and Android device verification remain outstanding.
- Spider Media source now supports local/document-provider files, direct HTTPS media URLs, HLS/DASH modules and playlist queue. Android Gradle/device playback still needs verification.

## Stage C — current development order

The development priority has been deliberately reordered around the main product value; see `docs/STAGE_C_EXECUTION_ORDER.md`.
1. C1: typed agent/task core, policy-gated tool broker, one-time approval, audit and independent verification for five bounded workspace actions (inventory, text search, text read, text write, and static JSON/XML/UTF-8 checks).
2. C2: real PTY terminal and process lifecycle.
3. C3: shared workspace integrating files, editor, terminal and browser.
4. C4: browser/media tool handlers.
5. C5: feed real task state into the Spider Widget.
6. C6: full Gradle/device integration and APK acceptance, while running quick checks throughout development.
7. C7: stabilization and regression testing.

Run `./scripts/verify_project.sh` as a continuous source/UI check; when Kotlin/JVM is available it also compiles the pure Agent Core sources and runs durable-task-store smoke scenarios via `scripts/test_agent_core_jvm.sh`. In an Android environment with JDK 17, Gradle and Android SDK Platform 35, also run `./scripts/android_build_local.sh`. The current workspace still has no Android SDK/adb/Gradle installation, so an APK is not claimed.

The app composition root initializes `SpiderAgentRuntime`, which uses a SQLite-backed `SqliteSpiderTaskRepository` with transactional migration of the legacy task file. The native task screen exposes recent history, selects or creates an app-private workspace, requests a structured multi-step plan, and executes only registered tools through policy and audit: `LIST_WORKSPACE_FILES`, `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, `WRITE_WORKSPACE_FILE`, and `CHECK_WORKSPACE_PROJECT`. Search is bounded; read/search/write require one-time approval; writes are atomic and replacing an existing file needs separate consent. Path traversal, symlinks, known secret paths, oversized text, and unregistered tools are rejected. Each supported result is independently checked, and output/file contents are not inserted into audit summaries or episodic learning memory. Arbitrary shell execution, project-test execution, file deletion, autonomous network access and external-app control remain unconnected. The server-side provider key stays out of Android. Backend credentials, APK assembly and real-device behavior remain unverified.

See `docs/ARCHITECTURE_v0.38.md` and `docs/IMPLEMENTATION_ORDER_v0.38.md` for the full system map, readiness values and strict implementation sequence.


## Android CI (v0.38)

A GitHub Actions workflow is included at `.github/workflows/android-build.yml`. It provisions JDK 17, Android SDK Platform 35 and Gradle 8.11.1, runs `testDebugUnitTest`, assembles the debug APK, and uploads the APK and test report as workflow artifacts.

This workflow has not run yet. The repository needs to be pushed to GitHub before the workflow can execute. The native Android build and APK are not yet verified.


## Stage C1 — history-driven self-learning

The Agent Core stores privacy-minimized episodic memory in SQLite with no app-level 64 MiB cap and no count-based eviction. A separate SQLite database stores the inverted token index and optional model-specific dense vectors; a durable outbox synchronizes index writes and supports replay after interruption. Episode retrieval is paged and bounded; dense search streams vectors and retains only top-K candidates in RAM. Previous binary memory/vector files are imported in batches and retained as `.migrated-v2` archives. Experiences remain workspace-scoped and time-aware, and user-rated plans never become verified execution successes. A persistent typed skill library recommends only recipes with independent verifier evidence; the bundled inventory recipe remains a candidate until it earns enough independent verified runs. This is memory-based behavioral adaptation, not base-model weight training. The current source checkpoint supports three shared-memory model routes: a user-loaded local GGUF model, Yandex AI Studio through the backend, and direct hosted OpenCode Zen API. The same on-device episodic memory and feedback loop informs local chat, Yandex chat/planning, and OpenCode Zen chat/planning. Cloud routes receive only bounded typed outcome/tool metadata; raw memory topic tokens stay on-device. This is memory-based behavioral adaptation, not model-weight training. Android native compilation, device inference and live provider validation remain pending. See `docs/SELF_LEARNING_ARCHITECTURE.md`, `docs/LOCAL_LLM_AND_YANDEX.md`, `docs/OPENCODE_ZEN_DIRECT_API.md` and `docs/CHANGELOG_C1_UNIFIED_PROVIDER_LEARNING.md` for design and limits.

## Stage C1 build gate

For a local Android build in an environment with JDK 17, Gradle 8.11.1 and Android SDK Platform 35 installed, run `./scripts/android_build_local.sh`. It runs Android unit tests, assembles the debug APK and prints the artifact SHA-256. The script fails explicitly if the toolchain is missing. See `docs/C1_BUILD_READINESS_2026-10-14.md` for the checkpoint status; static verification is not an APK build.


## Stage C1 — provider planner source checkpoint (historical; updated 17 Oct 2026)

The backend supports `/api/agent/plan` using an authenticated client token and an OpenAI-compatible provider configured only on the server. At the original planner checkpoint, only the read-only `LIST_WORKSPACE_FILES` tool was accepted. The active v0.39.1 checkpoint permits five registered workspace tools with per-action confirmation and verification. The fifth performs a bounded read-only JSON/XML/SVG/UTF-8 static check; it does not run code, tests, shell or builds. Live provider requests and APK/device behavior remain unverified. See `docs/CHANGELOG_C1_PROVIDER_PLANNER.md` and `backend/README.md`.

## Latest Stage C checkpoint — independent result verification
- `LIST_WORKSPACE_FILES` returns compact versioned inventory evidence and is checked against an independent filesystem walk.
- `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, and `WRITE_WORKSPACE_FILE` work only inside the selected app-private workspace. Search/read outputs are bounded and redacted; write is atomic, limited to supported text formats, and requires explicit approval (plus a separate overwrite checkbox for existing files). Search/read/write results are independently checked by repeat scan or read-back and SHA-256.
- Pure JVM tool tests exercise actual file listing/search/read/write, overwrite refusal, path traversal and symlink blocking, output secret redaction, audit privacy and independent verification. This is a source/test checkpoint, not an Android release. Gradle tests, APK assembly and device behavior remain unverified in this environment.


## Stage C1 — SQLite task history checkpoint (16 Oct 2026)

- Replaced the active task-history backend with a versioned SQLite repository while retaining `FileSpiderTaskRepository` as the legacy binary decoder. Import is one transaction; rows are compared by task ID and value before the import marker commits; the old `.spider` source is archived only after the successful commit. Unknown legacy schemas and future SQLite schema versions fail closed.
- Added normalized task/step tables, indexes for recent history and status-based recovery, atomic task+step saves, and no fixed history-row ceiling. The history UI now uses SQL-backed `COUNT(*)` and a bounded newest-first query, and startup recovery only loads in-flight tasks instead of materializing all history.
- Added Android instrumentation cases for migration/reopen, recent/count queries, preservation of unknown legacy schemas and fail-closed handling for a future database schema. These Android tests have not yet been run in this environment.


## Latest active checkpoint — SQLite task history (16 Oct 2026)

The current source checkpoint moves runtime task history from binary file writes to `SqliteSpiderTaskRepository`. The legacy file is retained as a migration input and is archived only after a separate read-only database integrity check and data comparison. The task-history screen reads a SQL count and latest-30 query, while startup recovery queries only possibly interrupted rows. Python tests (79/79) and a targeted Kotlin type-check against signature-only Android API stubs pass; Android Gradle build, instrumentation, APK and device testing remain unverified. See `docs/C1_SQLITE_TASK_HISTORY_REPORT_2026-10-16.md`.


## Latest Stage C1 — local core + GGUF + Yandex AI Studio (16 Oct 2026)

- Added a pinned llama.cpp v0.6.0 Android CMake/JNI inference source for `arm64-v8a` and `x86_64`, CPU-first GGUF loading, bounded chat-template generation and explicit unload. Model weights are not bundled; the app scans/imports user-provided `.gguf` files into its app-specific `llm` directory.
- Added local-first / Yandex-cloud / explicit local-then-cloud routing. At this 16 Oct checkpoint, the local planner used the strict tool allow-list parser and only `LIST_WORKSPACE_FILES` was executable; the 17 Oct workspace-tools checkpoint below supersedes that limit.
- Added an authenticated `/api/chat` route and first-class Yandex AI Studio support via `Authorization: Api-Key`, `OpenAI-Project`, and the fixed official API endpoint. API keys stay on the backend, never in the APK. The cloud chat sends only the recent messages chosen for cloud mode and bounded typed outcome metadata; topic tokens are excluded.
- “Self-learning” remains SQLite episode memory + retrieval and explicit feedback, not online fine-tuning of model weights.
- Python and contract tests now pass **86/86**, including mocked Yandex chat/planning/embeddings routes, API-key/header configuration, authentication, local routing, lazy fallback and model-import contracts. The full source/UI verification reports 130 unique HTML IDs, native asset sync, eight Chromium smoke scenarios and 14 Agent Core JVM smoke scenarios passing. Native CMake/Gradle compilation, device inference, Yandex credentials/live calls and APK are not verified in this environment.

## Latest Stage C1 — provider abuse guard (17 Oct 2026)

The backend now applies a configurable, per-token/per-route rolling-window cap (`SPIDER_PROVIDER_RATE_LIMIT_PER_MINUTE`, default 20/minute, maximum 600/minute) before calling the provider, returns HTTP 429 with `Retry-After`, and limits concurrent upstream provider calls to four. Tests cover quota enforcement, rolling-window reset, concurrent quota contention, invalid configuration, concurrency rejection and avoiding upstream calls after rejection. This is an in-process guard only: it resets on restart and is not coordinated across multiple workers/replicas, so a public deployment still needs a gateway-level limiter and spend quotas. It does not make a live provider call and does not change the unverified Android build status. See `docs/CHANGELOG_C1_PROVIDER_GUARD.md`.



## Previous continuation — floating Spider and capability map (17 Oct 2026; superseded by workspace-tools checkpoint below)

- The native Android overlay docks to the nearest edge, partially tucks after inactivity, peeks back out on touch, and exposes a quick panel plus a notification stop action. It still requires explicit Android overlay permission and cannot read/control other apps.
- The in-app Spider is layered above navigation/modals; the quick panel scrolls on short screens. The app-level spider retracts after idle and returns when touched.
- Added a runtime capability catalogue, used in local conversation prompts and mirrored in cloud-chat instructions. The 17 Oct continuation subsequently added three more real workspace handlers; see `docs/CHANGELOG_C1_REAL_WORKSPACE_TOOLS_LEARNING.md`.
- Validation at the floating-widget checkpoint: 96/96 Python tests, 14 Agent Core JVM smoke scenarios, 15 Media URL assertions, 131 unique HTML IDs, native asset byte-sync, and all 8 Chromium smoke scenarios. The aggregate `verify_project.sh` exceeded the environment time budget. Android Gradle/NDK build, APK and real-device overlay verification remain pending. See `docs/SPIDER_CAPABILITIES_AND_FLOATING_WIDGET.md` and `docs/CHANGELOG_C1_FLOATING_SPIDER_CAPABILITIES.md`.

## Language-model connections

ИИ Паук supports a user-provided local GGUF model, Yandex AI Studio through the configured Spider backend, and OpenCode Zen through its hosted model API. OpenCode Zen is a model provider only: project-file operations remain in the local Agent Broker and require the applicable policy checks and confirmations. The generic backend adapter supports HTTPS OpenAI-compatible providers. No separate OpenCode coding-agent server connector is included.

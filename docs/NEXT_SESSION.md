# Current continuation status — ИИ Паук v0.39.3 (19 October 2026)

## Current priority

The current source review removed the OpenCode Server connector. Direct OpenCode Zen API access remains as a hosted-model route, and Yandex AI Studio remains supported through the server adapter. Do not restore an OpenCode Server connection unless the project owner explicitly changes this decision.

The source audit fixed a real browser/backend integration mismatch. The HTML no longer treats `/api/health` as an auth/model readiness response and no longer routes ordinary chat into the disabled `/api/agent/step` endpoint. It checks the client token through `/api/client/check`, reads configured models via `/api/models`, and sends bounded authenticated chat to `/api/chat`. Backend CORS origins are explicit in `SPIDER_CORS_ORIGINS`; do not use `*`.

### Next actions in order

1. Run the GitHub Actions Android build workflow or an Android Studio setup with JDK 17, Android SDK 35, Gradle 8.11.1, NDK 27.2.12479018 and CMake 3.22.1. Confirm Gradle Sync, `testDebugUnitTest`, `connectedDebugAndroidTest`, `assembleDebug` and inspect the APK artifact. This environment has no Android SDK/Gradle/adb and cannot confirm the build.
2. On an Android emulator/device, verify initial WebView origin/CORS, WebView-to-backend token flow, stop/cancel behavior, local GGUF load/unload, native planner authentication, Keystore persistence, workspace tools, media playback, PTY lifecycle, overlay permission/restart and SQLite migration/recovery.
3. On a controlled test deployment, verify actual Yandex AI Studio credentials and OpenCode Zen API credentials with redacted logging; do not treat mock provider tests as live integration evidence.
4. Benchmark internal learning separately from base-model generation. Compare outcomes with/without retrieved memories, preserve workspace scope, and do not learn from planned-but-unexecuted actions or unverified results.
5. Only then expand runtime/tools. New operations must be registered, bounded, approval-gated where necessary, audit-logged and independently verified. `/api/agent/step` stays disabled until a properly verified executor exists.

### Verified in current source checkpoint

- Python unit/contract suite: **119/119 passed** in the focused final run.
- Chromium UI smoke: 8 scenarios pass; native Android bridge/device behavior is not tested.
- Kotlin/JVM Agent Core smoke: 15 grouped scenarios pass in the most recent completed focused run.
- Spider Media URL policy: 15 assertions pass.
- OpenCode Zen Kotlin source smoke and local LLM source type-check pass against test stubs; SQLite repository source type-check passes against signature stubs.
- Inline UI / packaged Android asset synchronization is covered by contract tests.

### Still unverified

- Android Gradle sync/compile and APK assembly; connected instrumentation tests and physical-device behavior.
- Real Yandex AI Studio and OpenCode Zen provider requests.
- Performance and practical learning benefit from long-term memory at realistic scale.
- Complete Linux runtime (the template intentionally remains `not_built`) and fully autonomous general-purpose command execution (not implemented by design).

Current docs: `docs/DEEP_AUDIT_AND_REMOVAL_REPORT_2026-10-19.md`, `docs/LOCAL_LLM_AND_YANDEX.md`, `docs/STATUS_MATRIX.md`, `backend/README.md`.

---

# Historical checkpoint record (kept for traceability; not the current execution plan)

# Next work checkpoint — ИИ Паук v0.38 / Stage C

## Current active priority: C1 — Spider Agent Core

Implemented source foundation:
- Task coordinator with validated transitions through planning, execution, approval, independent verification, completion/failure, pause/resume and cancellation.
- `HttpSpiderTaskPlanner` implements the planner port over an authenticated HTTPS backend endpoint. Live provider credentials/server are not configured yet.
- Typed `SpiderToolBroker` that dispatches only registered handlers after Sentinel policy checks, fails closed when a handler is missing, and requires an audit preflight before handler effects.
- `SqliteSpiderTaskRepository` is now the active task-history store; the legacy `FileSpiderTaskRepository` remains only as a versioned import/recovery decoder. The old binary file is archived only after transactional import and row-for-row verification.
- `SpiderAgentRuntime` is initialized by `MainActivity`, registers one concrete `LIST_WORKSPACE_FILES` handler through the policy broker and app-private audit sink, and exposes the remote planner.
- The workspace-list handler returns names only, applies depth/count limits and rejects canonical paths outside the selected workspace.
- A durable request ledger persists SHA-256 request-ID digests and blocks replay across broker recreation; invalid ledger state fails closed.
- Startup recovery converts interrupted planning/execution/approval/verification to safe terminal states and never automatically repeats a potentially in-flight action.
- Python/static/UI checks and a repeatable pure-Kotlin/JVM smoke test exercise the persistent repository, the first read-only handler and the fail-closed listing-result verifier.
- Native task screen includes the latest-30 persisted task-history panel, status/step progress, timestamps, sanitized details and safe audit references. Planner initialization now occurs after creating the durable task so provider configuration failures are retained as failed history entries.
- The native task screen supports workspace selection/creation, explicit one-time approval for the single read-only listing tool, and broker dispatch. The listing handler now attaches compact versioned evidence (workspace ID, item count, truncation flag, SHA-256 digest). `WorkspaceListingResultVerifier` performs a fresh NIO filesystem walk and compares the inventory to that evidence. A changed workspace, a missing/invalid proof, a truncated inventory, a mismatched count, or unconfirmed audit fails closed. The handler excludes symbolic links and internal workspace metadata. It still does not execute arbitrary tools or commands.

Current active C1 checkpoint — SQLite episodic memory and separate search index (16 Oct 2026):
1. `SpiderAgentRuntime` now uses `SqliteSpiderLearningMemory` (`spider-learning-v2.db`) and `SqliteSpiderEmbeddingIndex` (`spider-search-index.db`). The old `.spider` files remain migration inputs; after successful import they are preserved as `.migrated-v2` archives.
2. The live memory/index has no app-level 64 MiB byte cap and no count-based eviction. The old 64 MiB check is only for the legacy single-file format. If SQLite runs out of actual device storage, writes can fail; existing episodes must not be silently deleted.
3. Main DB tables include episodes, normalized topic tokens/tools, metadata and a durable index-change outbox. The separate index DB has indexed lexical postings and optional dense vectors keyed by task/model. Keyset pagination and bounded top-K reduce RAM usage; dense ranking currently still scans a model’s vector rows (O(N)), to be benchmarked before an ANN extension.
4. `SqliteSpiderMemoryInstrumentedTest.kt` adds Android tests for importing 540 episodes, paged reads, search-index updates/forget/clear, and 1,100 vectors. They have NOT been run because this environment lacks Gradle/Android SDK. `tests/test_sqlite_memory_contract.py` uses Python SQLite to validate DDL and scope-isolated query behavior.
5. LLM integration is intentionally deferred per user instruction. No live model/provider request has been made. Existing optional cloud embeddings remain off by default and are not invoked in this step.

Next actions in order:
1. Run the full Android Gradle compile/unit test/APK build through the configured workflow or an Android Studio environment. The build workflow has not been independently run from this session.
2. Run the new `SqliteSpiderTaskRepositoryInstrumentedTest` on an Android emulator: migrate/reopen binary history, exercise recent/count queries, and verify legacy/future schemas fail closed. Then run task/memory interruption and low-storage recovery cases.
3. Verify the existing memory diagnostics and outbox replay instrumented tests on the same emulator. The Python SQLite schema/contract checks are source-level checks, not Android-device validation.
4. Migrate the skill library/evidence separately after task history is stable; do not combine this with the task DB migration.
5. Benchmark 1k/10k/100k experiences and search latency before selecting an ANN index. Later add provenance-linked derived semantic summaries with explicit delete/rebuild semantics.
6. Only after these storage stages and user approval, return to LLM connection. Do not connect/call an LLM during this substage.

## Then, in order
- C2: real PTY terminal and process lifecycle; never expose generic unrestricted shell calls to the WebView.
- C3: shared workspace/file contract and integration.
- C4: browser and media tool handlers.
- C5: task-state event integration in the floating Spider Widget.
- C6: Gradle compile, unit tests, APK build, and Android device acceptance.
- C7: regression/stability gate.

## Verified in this checkpoint
- Latest focused Python suite: **66/66 passed** (backend, package-contract and runtime-validator tests).
- HTML/JavaScript static checks: 129 unique IDs; inline JS syntax clean; packaged Android HTML matches `app-web/ii-pauk.html`.
- Chromium UI smoke: 8 named UI scenarios pass with no uncaught page errors.
- Latest memory-diagnostics archive report: Python contract tests 74/74 passed; full Agent Core JVM smoke did not complete within the environment time limit. The pure JVM harness uses file-backed doubles for Android-only SQLite classes and is not a substitute for Android Gradle tests.
- New task-history migration checkpoint: Python/static suite 79/79 passed after adding SQLite task-history schema tests. The task repository and Android instrumentation test were source-level checked against minimal API stubs; no Gradle Android compile or device instrumentation run is claimed.
- `scripts/test_media_url_policy_jvm.sh`: **15 assertions passed**.
- HTML/JavaScript: 129 unique IDs and syntax-clean inline script; packaged Android asset matches web source. Chromium UI smoke: 8 scenarios pass, no uncaught page errors.
- Backend planner endpoint tests (mock provider): authentication, HTTPS provider URL policy, body/goal/response limits, unsupported-tool rejection, timeout/error redaction and valid typed plan response pass.

## Still not verified
- Android Gradle compile / `testDebugUnitTest` / `assembleDebug`.
- APK installation or native end-to-end behavior on an Android device.
- Live model provider (no credentials/server configured), Android end-to-end execution/approval behavior, device validation of task history and safe audit details, widget events, PTY/Linux runtime, browser/media handlers.


## Stage C — Spider Media source extension (15 Oct 2026)

The current archive extends Spider Media beyond local files: multi-select document picker, direct HTTPS sources, HLS/DASH module dependencies, queue controls, previous/next, URL-source validation, and safe display titles. The native `open_media` route may pass a validated HTTPS URL. JVM URL-policy smoke passed 15 assertions; project Python suite 56/56, HTML/Chromium smoke and existing Agent Core JVM smoke passed. Android Gradle compilation and device streaming are still pending because this working environment lacks Gradle and Android SDK. See `docs/MEDIA_CONTRACT.md` and `docs/CHANGELOG_C1_MEDIA_PLAYER.md`.


## Stage C1 — task history UI extension (16 Oct 2026)
- Added native history panel with the latest 30 persisted tasks, status/progress, timestamps, safe detail view, recovery state and short audit references.
- Audit-reference lookup reads a bounded tail of the app-private JSONL log and returns only allowlisted identifiers/enums/result codes; raw summaries and request IDs are not surfaced.
- Added source contract test `test_agent_task_history_is_persisted_visible_and_sanitized`.
- Native Gradle compilation/device validation is still required; do not call this an APK-tested milestone.

## Stage C1 — persistent task history checkpoint — 16 October 2026

Added the native task-history list (latest 30 by update time), task state and successful-step count, timestamps, shortened IDs, sanitized task/step/result text, recovery notice, detail view, and bounded safe audit-event references. Provider factory lookup is now deferred inside the planner call after a durable task record is created; missing configuration therefore yields a persisted failed task rather than an invisible attempt. Audit lookup reads at most the last 512 KiB/4096 lines and exposes only validated event prefixes, tool enums, dispositions, result codes and timestamps; it never returns raw summaries or request IDs. Python contract suite is 57/57; JVM Agent Core smoke is 12 grouped scenarios; Media URL smoke is 15 assertions. Native Activity syntax/type was checked against minimal Java Android API stubs, but official Gradle compilation, emulator/device UX, live provider call and APK remain unverified.


## Stage C1 — semantic self-learning and verifiable skill library (16 Oct 2026; previous checkpoint, superseded by SQLite checkpoint below)
- At that checkpoint the private binary store had a 64 MiB safety guard. The active runtime has since moved episodic memory and the separate search/vector index to SQLite; that old limit now applies only to the legacy import format, not to the active SQLite memory.
- Local semantic fallback uses deterministic multilingual concept vectors, light morphology and character n-grams; it is a transparent heuristic, not a neural embedding model.
- Optional cloud embeddings are behind a distinct opt-in (off by default); only sanitized topic tokens are sent to authenticated HTTPS `POST /api/agent/embeddings`, not complete prompts or content. The server validates token-only input and normalizes provider vectors. Live provider is not configured/tested.
- Embedding index persists by task/model ID; progressive backfill considers all stored episodes and processes up to 15 missing vectors per query batch. Backfill uses batch file write to avoid repeated whole-index rewrites.
- Persistent skill library stores typed tool recipes, preconditions, verifier/tool versions and unique-task evidence. It recommends only `VERIFIED` skills, never auto-executes; candidate requires 3 unique verified success tasks. After a verified failure, the skill remains `NEEDS_REVALIDATION` until 3 new consecutive independent successes.
- Targeted checks: Python **66/66**, Agent Core JVM **13 groups**, Media URL JVM **15 assertions**, HTML/Chromium 8 scenarios. The full wrapper script hit its aggregate time limit during redundant Kotlin compilation, but the component suites completed individually. Gradle Android compile, APK/device acceptance and real provider quality evaluation are still outstanding.


## Stage C1 — SQLite episodic memory and separate search index (16 Oct 2026)
- Live episode memory uses SQLite with schema, indices, topic/tool child tables, page/keyset access and no arbitrary 64 MiB application ceiling or count-based eviction. Device storage and SQLite failures still need to be handled explicitly.
- A second SQLite database stores inverted-search postings and optional dense vectors per task/model. Main-store outbox records index UPSERT/DELETE/CLEAR operations; index transactions commit before outbox acknowledgement, allowing replay after a crash.
- Old `.spider` memory/vector files are imported by bounded batches and retained as `.migrated-v2` archives.
- Python suite currently expected to validate schema contracts; Android instrumentation coverage (540 memory records / 1,100 vectors) is source-only and not run until Gradle/SDK is available.
- LLM integration explicitly deferred.


## Stage C1 — memory diagnostics checkpoint (16 Oct 2026)

Historical note for the 16 Oct memory-diagnostics checkpoint: explicit native memory/search diagnostics reports aggregate counts, file sizes including WAL/SHM, free/total app-volume space, schema versions and bounded SQLite `quick_check(1)` status tokens without paths, task goals, topic tokens, raw SQLite errors, vectors or model output. At that earlier checkpoint task-history migration was still pending; it is now implemented in the subsequent SQLite task-history checkpoint below. The diagnostics Android instrumentation test remains unrun because this environment lacks Gradle/Android SDK. LLM remains intentionally disconnected.


## Latest active checkpoint — SQLite task history (16 Oct 2026)

The runtime task repository now points to `SqliteSpiderTaskRepository` (`spider-task-history-v1.db`). It imports `agent/tasks-v1.spider` in a transaction with a pending marker, verifies imported task/step values, then checks database integrity and re-reads the data through a separate read-only SQLite connection. A final marker is committed only after that check, and the original is retained as `.migrated-sqlite-v1` only afterward. A crash while the marker is pending retries verification from the source file. If the old schema is unsupported, migration blocks and keeps the source; a future SQLite `user_version` is not downgraded. The history UI reads a SQL count and only the latest 30 tasks; startup recovery queries only possibly in-flight statuses. Android instrumentation tests cover import/reopen, >100 records, safe fallback on future schema and source preservation. Next gate: run Gradle `testDebugUnitTest`, `connectedDebugAndroidTest` and `assembleDebug`, then device acceptance. Skills remain on the prior repository until task-history migration is verified on Android.


## Previous checkpoint — floating Spider and capability map (17 Oct 2026; superseded by real workspace tools below)

- Native overlay edge-docks, partly retracts, reappears on touch, and opens app modules; stop action is available from notification. App-web spider is layered above in-app UI and has its own idle tuck/peek behavior.
- The live `SpiderCapabilityCatalog` is added to the local conversation prompt; backend chat and the local planner have matching guardrails. Capability states distinguish permission required, configuration required, source-unverified, UI-only, and not-connected states. Native Agent UI and in-app quick panel expose the capability summary.
- Agent Broker is still intentionally limited to one actual tool: `LIST_WORKSPACE_FILES`. Do not describe shell commands, browser page automation, external-app control, arbitrary file writes or APK generation as available tools.
- Validation reached: 96 Python tests, 14 Agent Core JVM scenarios, 15 media policy assertions, HTML syntax and asset sync (131 unique IDs), 8 Chromium smoke cases. The top-level verifier exceeded time budget after these component gates; no Android SDK/Gradle/NDK build or device test is claimed.
- Next acceptance gate: configure Android SDK/JDK/Gradle, run `./scripts/android_build_local.sh`, then test native overlay on a phone/emulator (permission grant/deny, edge drag, return-on-touch, rotation, safe-area/cutout, service notification stop and lifecycle).


## Direct OpenCode Zen model API — 17 Oct 2026

- Added native API-key/model setup under LLM settings, a fixed-host direct HTTPS client, AES-GCM + Android Keystore storage, model catalog refresh, and a 1-output-token test request before saving.
- Added OpenCode Zen as a chat mode and as a source of task-plan proposals; all Agent plan output still passes through `SpiderPlanParser` and the broker tool allow-list.
- Current provider routes: local GGUF, Yandex AI Studio through backend, and hosted OpenCode Zen model API. The separate remote coding-agent server connector was removed from the current source tree.
- Source smoke tests passed for model/protocol/key validation; Android APK, device Keystore and live authenticated request still need acceptance testing. See `docs/OPENCODE_ZEN_DIRECT_API.md`.


## Earlier checkpoint — shared learning across all provider routes (17 Oct 2026; before real workspace tools were added)

- Added shared `SpiderLearningContextCodec` to enforce the same hosted-provider payload contract: up to five outcomes, only allowlisted `LIST_WORKSPACE_FILES`, sanitized error code and clamped confidence. Topic tokens stay local.
- Fixed a gap: OpenCode Zen agent planning previously omitted learning hints; it now receives the typed memory context. Direct OpenCode Zen chat now uses the same relevant-memory retrieval already used by Yandex chat and local GGUF chat.
- One local episodic memory remains shared across local GGUF, Yandex AI Studio and OpenCode Zen. Terminal task outcomes and independent verification update experience; explicit task ratings adjust its weight. The provider's model weights are not trained; this is Spider-side memory-based adaptation.
- At that earlier checkpoint, Agent Broker had only the read-only `LIST_WORKSPACE_FILES` tool. The latest checkpoint below supersedes that capability list with three additional registered text-workspace handlers; shell execution and external-app control remain unavailable.
- Focused tests: Python suite 102/102, Agent Core JVM smoke 14 grouped scenarios, Media URL policy 15 assertions, OpenCode Zen Kotlin smoke passed, local LLM source check passed, historical connector smoke passed before connector removal, and SQLite task repository source typecheck passed. Chromium UI smoke exceeded the run time budget in this continuation and is not claimed as freshly verified here.
- Next gate: Android Gradle compile/unit/instrumentation tests + APK/device acceptance, then an end-to-end task with each configured provider and an explicit before/after memory benchmark. Live API keys were not used here.


## Latest checkpoint — real workspace tools + internal-brain learning (17 Oct 2026)

- Added real Agent Broker handlers for `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, and `WRITE_WORKSPACE_FILE`, alongside the existing inventory list. Local GGUF, Yandex backend and OpenCode Zen plan routes accept the same four-tool allowlist. The planner is checked against both the shared parser and the current registered-handler set.
- Native UI asks for a workspace-relative path or search query, captures exact text for a write, previews it, and requires one-time approval. The internal skill library seeds typed candidates for all four operations and records verifier/version evidence only after verified success while learning is enabled; each skill needs three distinct successful tasks before it is recommended and never executes itself. Replacing an existing file additionally requires an explicit overwrite checkbox. Writes are atomic; tools accept only known text-file formats, validate paths, block symlinks/secret-looking locations, bound file/search sizes, redact common credential formats and never expose tool output in audit summaries or durable learning memory.
- Independent `WorkspaceTextToolVerifier` repeats reads/search or reads the saved bytes back and verifies SHA-256. A missing handler, failed audit or verification mismatch cannot produce a completed task. No shell executor, test runner, downloader, delete tool, general network tool or external-app-control tool was added.
- The internal brain is Spider-owned episodic memory, shared across local GGUF, Yandex AI Studio and OpenCode Zen. It learns from actual successful/failed step IDs, verified task outcome, safe error codes and explicit user feedback; unexecuted planned tools are not stored as executed experience. It influences future retrieval/planning but does not alter the provider model's weights. File contents, raw outputs and credentials are excluded from long-term memory.
- Fresh verification for this checkpoint: Python suite 104/104; Agent Core JVM harness 15 grouped scenarios including real workspace text tools; Media URL Policy JVM 15 assertions; Chromium UI smoke 8 scenarios; OpenCode Zen source smoke (remote server connector removed); local-LLM Kotlin source typecheck; and SQLite repository source typecheck all passed individually. Python bytecode and shell syntax checks passed. The aggregate verifier was not used as a pass claim. No Android SDK/Gradle/NDK or device build is claimed; APK and live provider end-to-end validation remain outstanding.

## Continuation verification — 18 Oct 2026

- Re-ran `python3 -m unittest discover -s tests -p 'test_*.py'`: 104/104 passed.
- Re-ran `python3 scripts/smoke_runtime_ui.py`: all 8 browser scenarios passed; zero uncaught page errors. Native WebView/PTTY/device behavior remains not tested.
- `python3 -m py_compile backend/app/main.py scripts/*.py tests/*.py` and `bash -n scripts/*.sh` passed.
- Attempted `bash scripts/test_agent_core_jvm.sh` and the media policy harness in the same command; the combined command exceeded the execution time budget before returning results. Do not claim fresh JVM pass from this continuation; previous checkpoint results remain historical only.
- GitHub connector returned zero accessible repositories in this session, so no remote repository branch was modified. Work is based on the uploaded/local source checkpoint.
- Next implementation priority: an `APPLY_WORKSPACE_PATCH` tool that accepts a model-proposed unified diff only after rendering a preview, requires one-time user approval, checks expected pre-image hashes, applies atomically, and independently verifies post-image hashes. After that, add a bounded, allowlisted project-check runner; do not expose generic shell. Skill learning must require the test/verification outcome, not merely a successful file write.
- Android SDK/Gradle/device and live provider credentials remain unavailable here; no APK or live end-to-end claims.


## Latest checkpoint — AI-proposed single-file edits (18 Oct 2026)

- Added `SpiderWorkspaceEditParser` and provider dispatch for local GGUF, authenticated Yandex backend, OpenCode Zen and explicitly configured local-then-cloud mode. The model returns a complete replacement candidate only; no model path can directly write a file.
- Added `POST /api/agent/edit` with the existing bearer-token auth, separate route quota, bounded path/source/output schema, secret-pattern rejection, provider timeout/response bounds, and control-character validation. It only proposes text.
- Added native UI to select a relative file path, require cloud-transfer consent whenever the selected mode may send source to a hosted provider, prepare a proposal, render a complete diff (over 60,000 characters fails closed instead of truncating), require one-time approval, and require separate overwrite consent for an existing file.
- Extended the write handler with an expected source SHA-256 (or `MISSING`) guard checked before atomic move. A changed source is rejected. Post-write bytes are read back and passed through the existing independent write verifier. AI edits do not create intermediate directories.
- Current limits: source at most 1,900 characters; target must be a supported text file; full-file replacement, not a general multi-file patch; output capped by backend schema; no auto-run of tests, arbitrary shell, package install, download, delete, or APK generation. Local file text is not added to audit/task history/learning memory, but cloud mode sends the source to the explicitly selected hosted provider after consent.
- Verification: Python suite 111/111; Python compile and shell syntax pass; browser smoke 8/8 with no uncaught page errors; the Agent Core JVM harness freshly passed all 15 grouped scenarios with exit code 0, including the strict edit parser, complete diff preview bounds and stale-source controls. No Android SDK/Gradle/NDK/device or live-provider run.
- Next implementation gate: a bounded, allowlisted project-check runner with explicit user approval and structured result capture, beginning with source-defined known checks rather than a general shell. Multi-file patch application comes later, only after the native build/test path is dependable.


## v0.39.1 bounded static project checker — verified source checkpoint (18 Oct 2026)

- Registered `CHECK_WORKSPACE_PROJECT` end-to-end in the native broker, approval UI, shared parser, local GGUF/OpenCode Zen/Yandex backend plans, independent verifier, skill library and capability catalog.
- The checker is read-only and statically inspects bounded supported text files: JSON syntax, secure XML/SVG parsing and UTF-8 readability for other supported text formats. It never executes source, shell, tests, compilers, package managers, build tools or network.
- Limits: depth 8, 2,000 directory entries, 500 supported text files, 8 MiB content read, 64 KiB per file. Skips symlinks, sensitive paths, workspace metadata, and credential-looking bodies.
- Current verification: Python 111/111; Agent Core Kotlin/JVM 15 grouped smoke scenarios; browser 8/8; OpenCode Zen + connector source checks; local LLM Kotlin source typecheck via signature stubs; Python compilation and shell syntax checks. SQLite task repository typecheck timed out, so it is not marked passed. No Android APK/device or live provider verification.
- `RUN_PROJECT_TESTS` remains unimplemented; next implementation stage is a separately approved, bounded allowlisted project-check runner, followed by Android build/device acceptance.

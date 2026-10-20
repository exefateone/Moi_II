# ИИ Паук v0.38 — Stage C1 Agent Core checkpoint

**Date:** 15 October 2026  
**Product version:** remains v0.38. This is an incremental source checkpoint, not a release APK.  
**Current stage:** C1 — Agent Core and task orchestration.

## Implemented in this checkpoint

- `SpiderTaskCoordinator.kt` validates task goals/plans and enforces task/step lifecycle transitions: queued, planning, running, approval gate, verification, completion/failure, pause/resume and cancellation.
- `SpiderToolBroker.kt` dispatches only registered typed handlers, checks policy, refuses missing handlers, consumes request IDs within the current process and requires an `execution_started` audit record before handler side effects.
- `FileSpiderTaskRepository.kt` provides a versioned (schema v1) durable task store with bounded field/count parsing, write-to-temp + `fsync` + replace, corruption backup, and refusal to overwrite a store produced by a future schema.
- `SpiderAgentRuntime.kt` composes the durable repository, coordinator, workspace repository, tool broker and app-private audit log. `MainActivity` initializes this runtime.
- `WorkspaceFileListHandler.kt` is the first concrete low-risk tool handler. It lists entry names only inside the selected workspace, uses depth/count caps, skips canonical paths outside the workspace and never reads file contents.
- Added JUnit test sources for persistence and workspace-list path safety. Added `scripts/test_agent_core_jvm.sh`, a repeatable pure Kotlin/JVM compile and smoke test.
- Updated Stage C execution order, road map, status matrix and agent contract docs.

## Verification performed

| Check | Result |
|---|---|
| Existing Python contract/runtime tests | **35/35 passed** |
| HTML/JavaScript static check | **PASS** — 128 unique IDs; inline JavaScript syntax clean |
| Native HTML asset sync | **PASS** |
| Chromium UI smoke | **PASS** — 8 named UI scenarios; no uncaught page errors |
| Pure Kotlin/JVM core compile | **PASS** |
| Agent Core JVM smoke scenarios | **5/5 passed** — durable round-trip, multi-task update, corruption backup/recovery, future-schema preservation, coordinator persistence and a brokered workspace-list handler with audit (these are five grouped smoke scenarios, not JUnit/Gradle tests) |
| ZIP integrity / SHA-256 manifest | To be verified after packaging |
| Official Android Gradle/JUnit build | **NOT RUN** — Gradle/Android SDK unavailable in this environment |
| APK output / physical-device acceptance | **NOT CLAIMED** |

## Explicit limitations

- No live LLM/model-provider adapter is connected; `SpiderTaskPlanner` remains an interface port.
- The durable repository and one handler are initialized in the Android app composition root, but the coordinator/tool broker are not exposed through a user-visible task manager or the widget UI.
- No Android confirmation UI, independent evidence verifier or event/status flow is wired.
- Request-ID replay defense remains process-memory only; persisting tasks does not make replay protection durable.
- Only `LIST_WORKSPACE_FILES` is registered. File reading/writing/editing, browser/media automation, terminal/PTy, Linux, package installs, deletion and external sending are not implemented as agent handlers.
- Android Gradle compilation, official JUnit tests, APK creation and on-device behavior remain unverified.

## Next C1 items

1. Add a real model-provider adapter with structured bounded output, timeout/quota limits and secret-safe configuration.
2. Connect a visible task/status/approval UI to the runtime without adding a generic shell bridge.
3. Add durable replay protection and explicit task recovery/migration behavior.
4. Run official Gradle tests/build and Android-device acceptance when the Android toolchain is available.

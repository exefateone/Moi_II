# Stage C1 — Spider Agent Core foundation

Date: 2026-10-15

## Added

- Task coordinator with validated lifecycle transitions: queue, plan, run steps, approval pause, verification, completion/failure, pause/resume and cancellation.
- Provider-independent `SpiderTaskPlanner` port.
- Typed tool broker that dispatches only registered handlers and routes requests through `SpiderPolicyEngine`.
- One-time request ID consumption within the current process.
- Fail-closed audit preflight before a tool handler can cause a side effect.
- Shared secret-pattern redaction for audit summaries and tool results. A new regression test found a token-redaction edge case (`Authorization: Bearer …`); redaction order was corrected and the leak test now passes.
- Unit tests for coordinator transitions, one-time approvals, handler failures, audit failure and replay defense.

## Not included in the initial core-only checkpoint / not claimed

- No LLM or hosted provider adapter.
- At this initial checkpoint, no real tool handler had been registered; a follow-up added one read-only workspace-list handler. Shell/PTY and write/edit executors remain unimplemented.
- No persistence beyond in-memory task repository.
- No Android UI/overlay wiring for task state.
- No confirmed Kotlin/Android build or APK artifact.


## Follow-up: durable task repository — 15 October 2026

- Added `FileSpiderTaskRepository.kt` behind the existing `SpiderTaskRepository` interface. It serializes task and plan-step state using a versioned schema, bounded string/count decoding, a same-directory temporary file, `fsync`, and atomic replacement when supported.
- Malformed/truncated stores are preserved as `.corrupt-*` before a clean store may be written; the repository exposes a recovery notice. Files with a future schema version are not renamed or overwritten, preventing an older app from silently destroying newer state.
- Added JUnit tests and a repeatable `scripts/test_agent_core_jvm.sh` smoke test for round-trip persistence, multiple tasks/update behavior, corruption recovery, future-schema protection and coordinator/repository compatibility.
- Limitation at the persistence-only sub-checkpoint: the app had not yet instantiated this repository. A subsequent integration step below wires it into the runtime; task coordination still has no user-visible task UI. The smoke test is not a Gradle or Android-device test.

- Follow-up integration: `SpiderAgentRuntime` selects the durable repository under app-private `filesDir` and is initialized by `MainActivity`. It registers one concrete `LIST_WORKSPACE_FILES` handler behind the policy broker and audit sink.
- Added a handler implementation that lists workspace names only, applies depth/count limits and refuses to follow canonical paths outside the selected workspace. Pure Kotlin/JVM smoke and JUnit test sources cover broker/audit integration, missing workspace and optional symlink escape.

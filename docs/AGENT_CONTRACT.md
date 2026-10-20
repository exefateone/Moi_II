# Spider Agent contract — v0.38 / Stage C1

## Purpose

Define stable contracts before connecting a model-driven tool loop. A model may propose a plan; it cannot assign itself risk/permission levels or submit arbitrary shell text for execution.

## Current source files

- `SpiderAgentModels.kt`: task/step state, typed tool IDs, tool request and policy result. There is deliberately no generic shell tool ID.
- `SpiderPolicyEngine.kt`: risk assignment and fail-closed approval decisions.
- `SpiderTaskCoordinator.kt`: validated state transitions, plan validation, one active step at a time, approval wait/resolve, pause/resume, cancellation and independent-verification gate.
- `SqliteSpiderTaskRepository.kt`: active versioned task/step database with atomic task updates, indexed recent/recovery queries, transactional import verification from the legacy binary file, and fail-closed future-schema behavior.
- `FileSpiderTaskRepository.kt`: legacy versioned binary decoder retained for migration and recovery; it is no longer the active runtime write path.
- `SpiderAgentRuntime.kt`: app-private composition root; it selects durable task storage, registers the five supported workspace handlers behind the broker (list/search/read/write/static-check), wires independent verifiers and exposes the configured planner to the native task screen. `MainActivity` initializes this runtime.
- `WorkspaceFileListHandler.kt` and `WorkspaceTextTools.kt`: bounded inventory plus text search/read/write handlers confined to the selected workspace. Text reads redact common credentials; writes are atomic and save only exact user-entered text after confirmation. Independent verification is required; there is no generic shell handler.
- `SpiderToolBroker.kt`: registered typed handler dispatch, policy gate, request-ID replay defense, mandatory pre-execution audit and shared secret redaction.
- `SpiderAuditLog.kt`: Android app-private append-only JSONL audit implementation of `SpiderAuditSink`.
- `HttpSpiderTaskPlanner.kt`: HTTPS-only client for the authenticated backend planner route. It rejects redirects, bounds request/response sizes and timeouts, binds the response to the task ID, ignores model-supplied status/result fields, and accepts only five implemented workspace tools: `LIST_WORKSPACE_FILES`, `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, `WRITE_WORKSPACE_FILE`, and `CHECK_WORKSPACE_PROJECT` (bounded static JSON/XML/SVG/UTF-8 checks only; no code execution). Each tool is guarded by the broker; content reads/searches/writes need explicit approval and independent verification.
- `SpiderProviderConfigStore.kt`: encrypts the app-to-backend bearer token using Android Keystore AES-GCM; it never stores the model-provider API key.
- `SpiderAgentActivity.kt`: native task screen for backend settings and plan-only requests. Network requests run on a worker thread. It explicitly tells the user that planning does not execute the proposed tool.

## Task lifecycle

```text
QUEUED -> PLANNING -> RUNNING -> VERIFYING -> COMPLETED
                    |     |           |
                    |     +-> WAITING_FOR_APPROVAL -> RUNNING
                    |     +-> PAUSED <-> active state
                    +-> FAILED
                    +-> CANCELLED
```

A policy `ALLOW` decision is only authorization, not evidence an operation succeeded. A task remains `VERIFYING` after all tool steps report success and can become `COMPLETED` only after a separate caller reports successful verification.

## Tool invocation requirements

- Every executable step has one `SpiderToolId` and one registered `SpiderToolHandler`.
- The broker calls `SpiderPolicyEngine` before dispatch. A missing handler fails closed and does not prompt the user to approve an impossible operation.
- The audit sink must successfully record `execution_started` before the handler is invoked.
- A request ID is consumed when execution is about to start and cannot be replayed within the current broker process.
- Resource references must be resolved and path-checked by each native handler; display names are never file paths.
- No raw shell command field, untrusted webpage instructions, provider secrets or entire file contents may be written to audit events.

## Deliberate limitations in Stage C1

- `InMemorySpiderTaskRepository` remains useful for isolated tests. The app composition root uses `SqliteSpiderTaskRepository` for task history. On first access, it migrates `agent/tasks-v1.spider` inside a SQLite transaction, compares all task and step values, then records the import marker; the old source is archived only after commit. A corrupt/unknown legacy schema is not overwritten. The native history UI uses bounded recent/count queries; task-state events in the floating widget are still pending. Future SQLite schema changes require explicit non-destructive migrations.
- `SpiderTaskPlanner` is provider-independent; `HttpSpiderTaskPlanner` and the backend provider adapter are implemented in source. A live provider is not connected until server credentials/deployment are supplied.
- Four workspace handlers are registered: list/search/read/write for supported text files in the selected app-private workspace. A generic shell/test runner, autonomous model-generated code patch, browser automation, media action, download/install/delete and external-send actions are not implemented or registered.
- Tool execution uses the injected durable one-shot request ledger; pure JVM smoke covers replay rejection across broker recreation. SQLite/Android process-restart acceptance still depends on Android instrumentation/device tests.
- The native UI requests per-action confirmation and separate overwrite consent, and independently verifies each implemented workspace operation. Android Gradle/device acceptance remains unrun in this environment.
- No WorkManager-based durable background execution, checkpoint recovery, terminal/PTY handler or agent task-state widget integration is connected.

This is a safer Agent Core foundation, not yet a fully autonomous agent.


## Provider adapter checkpoint — 15 October 2026

The source package now includes an authenticated FastAPI `/api/agent/plan` route backed by an OpenAI-compatible chat-completions API, bounded request/provider output, server-side provider keys, HTTPS-only endpoint validation, timeouts and redacted error handling. The Android task screen stores only the app-to-backend token encrypted with Android Keystore and can request a plan once a deployed/configured backend is supplied. The allowed plan tools are `LIST_WORKSPACE_FILES`, `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE` and `WRITE_WORKSPACE_FILE`. Plans are not automatically executed: the native UI gathers the relative path/search query/text, previews writes, and asks for explicit one-time approval. Mocked backend tests pass; no live provider credentials/server are configured, and the Android app has not yet been built or run on device.

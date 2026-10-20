> **Current Stage C priority:** the implementation order was deliberately reordered around the Agent Core. See `STAGE_C_EXECUTION_ORDER.md`. The numbered v0.39+ sections below remain roadmap labels and historical release sequence, not a requirement to postpone all agent-core work until v0.42. Android build/device acceptance remains a parallel technical gate.

# Roadmap — ИИ Паук v0.38 onward

## v0.36 — Native browser source baseline
- [x] WebView route, URL policy and bridge guard scaffolding.
- [ ] Android compile/device evidence remains outstanding.

## v0.37 — Agent + Media Foundation
- [x] Agent/task/tool contracts and policy/audit source.
- [x] Media3 Activity/service source and menu routes.
- [ ] Gradle compile, policy tests, local audio/video playback and background session QA.

## v0.38 — Spider Overlay + Code Studio [source/static tested; Android build unverified]
- [x] Floating native overlay service source with drag, quick menu, status hook and foreground notification.
- [x] Explicit overlay-permission flow.
- [x] Widget shortcuts to core areas.
- [x] HTML editor, isolated demo preview, HTML save/download fallback.
- [x] Static project checks and Chromium smoke pass.
- [ ] Gradle/unit tests and real-device acceptance.

## v0.39 — Code-to-output / APK Build Runner
- [x] HTML demo and `.html` export source path.
- [ ] Isolated Android build runner with pinned toolchain and resource limits.
- [ ] Real APK artifact, logs, tests/checksum and user-controlled signing.

## v0.40 — PTY terminal + workspace integration
- [ ] Real PTY, interactive process control, cwd containment and lifecycle tests.

## v0.41 — Spider Linux userland
- [ ] ABI-specific Linux userspace build, safe installation/update and device validation.

## v0.42 — Agent Executor + Task Manager
- [x] Coordinator, typed tools, Sentinel approval, execute/verify/audit and cancellation source foundation.
- [x] Versioned durable task repository connected to `SpiderAgentRuntime` + JVM smoke tests.
- [x] First read-only workspace-list handler registered through policy broker and audit.
- [x] Durable request-ID replay defense with app-private ledger and restart recovery source tests.
- [x] Native task history/progress UI with sanitized event references and explicit user feedback.
- [x] Episodic self-learning memory, scope-isolated retrieval, recency weighting and forget/clear controls (source/JVM-verified, not device-verified).
- [ ] Feed actual task state into the global Spider widget.
- [ ] Confirm the task/history/learning flow in a real Android build and on device.

## v0.43 — Spider Code loop
- [ ] Expand beyond read-only listing: inspect → plan → edit → test → HTML preview or APK build → verify → report, adding each typed handler with separate policy and independent tests.

## v0.44+ — Advanced memory, backend, remote worker and media
- [x] Add local multilingual semantic retrieval and explicit opt-in server embeddings with input validation and workspace-scope isolation; real-provider quality/poisoning evaluation remains open.
- [x] Add file-backed typed skill registry, candidate gating, independent verifier evidence, explicit preconditions and regression revalidation; expansion beyond the first read-only skill waits for more real handlers.
- [x] Move active episodic memory and separate lexical/dense search index to SQLite with no arbitrary 64 MiB app cap, bounded/keyset reads, outbox-based index repair and batch migration from legacy files (source/Python contract/type-check only).
- [ ] Execute SQLite Android instrumentation tests, build APK, test migration/recovery/low-storage behavior on emulator/device, and profile 1k/10k/100k episodes before selecting an ANN vector index.
- [ ] Authenticated remote worker and advanced media processing/speech integrations.
- [ ] Optional fine-tuning as a separate explicit-consent/offline-evaluation project; no auto-training on personal memory.

## Release gates
A feature is “ready” only after appropriate source tests, Android compilation, end-to-end checks and device evidence. HTML checks alone do not prove Android behavior. Do not claim PTY, Linux, complete agent, active cross-app overlay or APK generation without the relevant proof.

# ИИ Паук v0.38 — C1 durable request replay protection and startup recovery

**Date:** 15 October 2026  
**Checkpoint:** incremental source update; not a release build or APK.

## Added

- New `SpiderRequestLedger` contract with an in-memory implementation for isolated tests and `FileSpiderRequestLedger` for Android runtime use.
- The file-backed request ledger stores SHA-256 digests of one-time request IDs, not the raw IDs. It uses a schema/version header, bounded entry count, same-directory temporary file, fsync and atomic replacement where supported.
- Corrupt, unsupported, oversized, full or unwritable request-ledger storage fails closed. The code does not silently reset an invalid ledger, which could permit a previously used request to execute again.
- `SpiderToolBroker` claims an ID durably before the pre-execution audit event and before handler side effects. It rejects already-claimed request IDs across broker recreation and does not dispatch if the ledger cannot safely claim the ID.
- `SpiderAgentRuntime` now uses `agent/request-ledger-v1.spider` under app-private storage.
- `SpiderTaskCoordinator.recoverInterruptedTasks()` reconciles persisted planning, running, approval-waiting and verifying tasks after process restart. Interrupted actions are marked failed, approval-waiting steps are skipped, and potentially in-flight steps receive an unknown-outcome warning. No task is automatically replayed.
- Startup recovery is invoked by the runtime. If task storage is unavailable or uses a newer schema, the native task screen displays a warning rather than silently claiming recovery succeeded.
- Added JVM smoke coverage and Android JUnit source for durable claims, replay denial, corrupt-ledger fail-closed behavior, unknown execution outcomes and expired approval.

## Verification

- `scripts/verify_project.sh`: **56/56 Python tests passed**.
- HTML/JavaScript: **129 unique IDs**, inline script syntax clean, packaged Android HTML matches `app-web/ii-pauk.html`.
- Chromium UI smoke: **8 scenarios passed**, no uncaught page errors.
- `scripts/test_agent_core_jvm.sh`: **10 grouped smoke scenarios passed**, including durable cross-instance replay denial, corrupt ledger preservation, interrupted execution recovery and approval expiry.
- `SHA256SUMS.txt` is regenerated and verified against the packaged source tree.
- Linux runtime release gate remains **NOT READY** by design because the bundle is a `not_built` template.

## Not verified

- Android Gradle compile, official JUnit/instrumentation execution, `assembleDebug`, APK generation or device behavior.
- Live model-provider request; endpoint/provider credentials remain deployment configuration, not included in this archive.
- Cross-process/multi-process ledger concurrency. The app currently uses the same Android process for these components; the current file lock coordinates instances inside that process.

## Next C1 work

1. Extend `WorkspaceListingResultVerifier` to compare the tool's bounded inventory evidence with an independently generated filesystem inventory.
2. Add persisted, user-visible task history and safe task-progress events.
3. Configure/deploy an HTTPS backend and perform a live model-planning call.
4. Run Gradle tests/build and complete device acceptance when Android SDK/build access is available.

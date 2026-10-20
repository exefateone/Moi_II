# ИИ Паук v0.38 — Stage C1 workspace selection and explicit tool approval

**Date:** 15 October 2026  
**Product version:** v0.38 (incremental source checkpoint; not a release APK).

## Added

- The native task screen can list existing app-private workspaces and create a named workspace without exposing its physical path to the UI.
- A planner result only exposes execution when it contains exactly one currently implemented `LIST_WORKSPACE_FILES` step. Unknown, multi-step, and unimplemented plans remain non-executable from this UI.
- Before calling the handler, the UI displays the goal, tool action, chosen workspace and its read-only effect in an explicit confirmation dialog.
- The one-time approval ID is created before the dialog and passed unchanged to the typed broker only after the user approves. Cancel does not call the tool handler.
- The native flow runs the handler on a worker thread. It uses the typed broker, request policy, app-private audit log and safe workspace handler; it does not expose shell commands or file-content reads.
- Added `WorkspaceListingResultVerifier`, which checks the expected tool result code and summary shape, audit persistence, and current workspace availability. A task moves to `COMPLETED` only when these checks pass; otherwise it fails closed.
- At this historical checkpoint the verifier was intentionally narrow; the later `CHANGELOG_C1_INDEPENDENT_INVENTORY.md` records the independent evidence upgrade.

## Verification

- The Python/static contract suite and browser smoke checks are recorded by `scripts/verify_project.sh`.
- Source-contract checks cover workspace selection, explicit approval, request-bound approval ID, typed broker dispatch and the no-shell boundary.
- Python suite: **55/55 passed**. Pure Kotlin/JVM Agent Core smoke: **6 grouped scenarios passed**, including verifier fail-closed cases and hiding internal workspace metadata from user-facing listing.
- Android SDK compilation, Android JUnit/instrumentation tests, live provider call, APK build and device execution are still not verified in this environment.

## Remaining C1 work

1. Expand verification to compare the resulting inventory with a separately generated, bounded filesystem view.
2. Make approval/replay bookkeeping durable across app restart and add startup task recovery.
3. Execute a live provider request after the user deploys/configures the HTTPS backend. Keep provider keys on the server only.
4. Run Gradle unit/instrumentation tests, build APK and complete device acceptance.

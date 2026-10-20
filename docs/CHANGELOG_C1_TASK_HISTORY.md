# Stage C1 — persisted task history (2026-10-16)

## Added
- Native Agent task screen now includes a persisted history of the latest 30 tasks, ordered by last update.
- Each history card displays a localized state, successful-step progress, a shortened task ID, and update time. Selecting a task opens a detail view with safe/redacted goal/result text, step states, sanitized error code, and timestamps.
- Recovery state is visible in the history area. Interrupted tasks remain terminal after startup recovery; no action is automatically replayed.
- Audit history exposes only bounded allowlisted references (short event reference, timestamp, validated tool enum, validated disposition, and restricted result code). Raw summaries, request IDs, URLs, tokens and filesystem paths are not returned by the safe-reference API.
- Audit file scans are limited to the last 512 KiB and at most 4096 lines per request.
- Added package contract tests for the history panel and safe audit references.

## Validation limits
The Python/static/browser checks can validate source contracts and the web UI, but cannot confirm native Activity behavior. Gradle/Android SDK compilation, emulator/device UI validation, and live backend planning remain pending. This is source implementation, not an APK.

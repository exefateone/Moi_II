# Stage C1 — Memory and search diagnostics

**Checkpoint:** 16 October 2026 · product version remains v0.38.
**LLM integration:** intentionally deferred; no model client was added or invoked.

## Implemented

- Added an explicit “Диагностика памяти и поиска” action in the native task/learning screen. Diagnostics run on the activity worker thread; `PRAGMA quick_check(1)` is not run as part of normal startup or every memory refresh.
- A diagnostic snapshot reports episode count, topic-token count, pending outbox rows, lexical posting count, vector count, distinct embedding model count, main/search database sizes including SQLite `-wal` and `-shm` files, database schema versions, available/total app-volume space, and sanitized SQLite health statuses.
- Health status is intentionally limited to `ok`, `integrity_warning`, and `check_unavailable`; raw SQLite errors are not included in the snapshot.
- The panel does not expose database paths, workspace paths, task goals, memory topic tokens, model outputs, embedding data, credentials, or raw event records.
- Existing storage summary language now clearly states there is no fixed episode-count eviction. Device free space remains finite; failure to write must be surfaced, and the app does not delete older episodes to make room.
- Added Android instrumentation coverage that creates a task-memory episode and a vector, checks counters/file sizes and both `quick_check` statuses, and verifies the snapshot does not include a path or task goal.
- Added Python source/SQL contract tests to exercise the diagnostic table-count queries and SQLite health checks with Python's SQLite engine.

## Verification

- Python tests: **74/74 passed**.
- HTML / inline JavaScript: 129 unique IDs; syntax check passed; Android packaged HTML matches source.
- Chromium smoke test: 8 scenarios passed, no uncaught UI errors.
- Runtime release gate remains intentionally `NOT READY` because the Linux runtime manifest is still a template marked `not_built`.
- The full aggregate script reached the Kotlin Agent Core smoke compile but did not complete before the environment time limit. Do not count that suite as passing for this checkpoint.
- The new Android instrumentation test was authored but not run: Gradle and Android SDK/device execution are unavailable in this environment. No APK was built.

## Next, still without an LLM

1. Run the Android instrumentation diagnostics and low-storage/outbox-recovery tests on a real emulator/device.
2. Migrate the durable task-history repository to SQLite in its own checkpoint, preserving prior binary data until the migration is validated.
3. Migrate the skill library/evidence separately in a later checkpoint.
4. Benchmark 1k/10k/100k experiences before deciding whether a vector ANN index is warranted.
5. Add provenance-linked derived semantic summaries only after task history and skill evidence have safe delete/rebuild semantics.

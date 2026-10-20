# Changelog — C1 SQLite task history (16 October 2026)

Product release version remains **v0.38**. This is an incremental source checkpoint, not a release APK.

## Added

- Added `SqliteSpiderTaskRepository`, the active app-private task-history store, with `task_history`, `task_steps`, and import metadata tables.
- Enabled SQLite foreign keys and WAL, plus indexes for newest-first history and startup recovery status queries.
- Added atomic task-plus-step writes and SQL-backed `count()`, bounded `recent(limit)`, and filtered `recoverableForStartup()` APIs.
- Migrated runtime task-history usage from `FileSpiderTaskRepository` to SQLite. The legacy repository remains as a decoder for `agent/tasks-v1.spider`.
- Added a one-time migration transaction and marker. Source data is read with the existing versioned/validated decoder; imported task and step state is read back and compared before committing the marker. A `pending` marker is committed with imported rows; a separate read-only connection then runs `PRAGMA quick_check(1)` and compares the re-read tasks. The marker changes to `imported` only after those checks; the original file is archived as `.migrated-sqlite-v1` afterward. A restart with a `pending` marker repeats verification instead of blindly trusting the database.
- Unknown legacy schema versions fail closed and preserve the legacy source. SQLiteOpenHelper's future-version downgrade guard is not bypassed; no tables are dropped to recover.
- Changed the history screen to use SQL `COUNT(*)` and retrieve the latest 30 records without loading the whole history table. Startup recovery now asks the repository for only active/interrupted candidates.
- Exposed sanitized repository recovery/storage notices in the task-history status area.
- Added `SqliteSpiderTaskRepositoryInstrumentedTest.kt` for migration/reopen, pending-marker crash recovery, count/recent ordering, update behavior, preservation of unknown legacy schemas, and fail-closed behavior for future SQLite schema versions.
- Added a targeted Kotlin type-check script using signature-only Android API stubs and Python/SQLite contract tests that execute the actual DDL.
- Extended the Android GitHub Actions workflow to run `connectedDebugAndroidTest` on an API 35 emulator before packaging the debug APK.

## Verification performed for this checkpoint

- Python/source/SQLite contract suite: **79/79 passed**.
- Task repository + task coordinator + legacy decoder Kotlin type-check: **PASS** using signature-only Android API stubs.
- SQLite task schema executed with Python's SQLite engine; foreign-key cascade behavior verified.
- Full `scripts/test_agent_core_jvm.sh`: attempted, but did not complete within the environment time limit; it is **not** counted as passing.

## Not verified

- Android Gradle compilation and official `testDebugUnitTest`.
- Android `connectedDebugAndroidTest` (instrumentation tests have been authored and the CI workflow extended, but CI has not run here).
- APK assembly, installation, or use on a real device/emulator.
- Device-level crash/low-storage behavior and task-history migration with real Android SQLite.
- Live model-provider connection remains intentionally deferred.

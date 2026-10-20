# ИИ Паук v0.38 — C1 SQLite task-history checkpoint

**Date:** 16 October 2026  
**Product version:** remains v0.38  
**Artifact type:** incremental source archive, not APK

## What changed

The app runtime now constructs `SqliteSpiderTaskRepository` instead of writing new task history into `FileSpiderTaskRepository`. The previous binary format remains supported for migration/recovery; it was not deleted.

The SQLite store contains parent task rows, ordered step rows, and metadata recording the outcome of the one-time binary import. Saving a task and its steps is transactional. Recent history and row count are SQL queries, and startup recovery loads only rows whose task or paused resume state might represent work interrupted by process termination. The UI still shows the latest 30 tasks and now surfaces sanitized storage/migration notices.

## Migration safety

1. The old binary store is read through its existing schema decoder and field validations.
2. Task rows and ordered step rows are inserted in a single SQLite transaction.
3. The imported rows are read back and compared with the decoded source before the migration marker is committed.
4. The original binary file is moved to a `.migrated-sqlite-v1` archive only after transaction success. If schema decoding or verification fails, the original source remains untouched and the app can block unsafe task execution.
5. An unknown/future legacy schema is not renamed as corrupt, and the code contains no `DROP TABLE` recovery path. A future SQLite database version is left to SQLiteOpenHelper's fail-closed downgrade behavior.

The history screen's latest-30 query avoids reading every old task for a routine refresh. Startup recovery is similarly filtered at the repository layer. The compatibility `all()` method remains available for tests and workflows that require a full set.

## Test coverage added

The Android instrumentation suite now contains cases for:

- importing task and step states, reopening the repository, and verifying the retained migration archive;
- count and newest-first recent queries with 120 stored tasks;
- preserving an unknown legacy schema byte-for-byte and blocking migration;
- preserving a database whose `user_version` is newer than the supported schema.

The Python contract suite extracts and executes the schema DDL with SQLite, checks cascading task-step deletion, and asserts the transactional migration and bounded-query contracts. A targeted `kotlinc` check compiles the touched model/coordinator/repository source against signature-only Android API stubs.

## Results

- Python tests: **79/79 passed**.
- Targeted Kotlin type-check: **PASS** (signature stubs only).
- Full Agent Core JVM smoke: attempted but timed out in the current environment; not counted as pass.
- Gradle compile/unit tests, Android instrumentation, APK, emulator/device tests: **not run**. The current environment does not have Gradle, Android SDK, or `adb` available.
- Live LLM/provider calls: intentionally not made.

## Next gate

Run the project GitHub Actions workflow or Android Studio build. The workflow now has a real API 35 emulator instrumentation step (`connectedDebugAndroidTest`) before debug APK assembly. Once CI succeeds, inspect both test reports and APK artifact, then perform a device QA pass including process restart and storage failure. Only after task-history persistence is verified should the skill library/evidence be migrated to SQLite in its own checkpoint.

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC_BIN="${KOTLINC:-$(command -v kotlinc || true)}"
if [[ -z "$KOTLINC_BIN" ]]; then
  echo "SKIP: kotlinc unavailable; run this check in a Kotlin/JVM environment." >&2
  exit 0
fi
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
"$KOTLINC_BIN" -nowarn \
  "$ROOT/tests/kotlin/sqlite_task_typecheck/android/content/AndroidApiStubs.kt" \
  "$ROOT/tests/kotlin/sqlite_task_typecheck/android/database/CursorStub.kt" \
  "$ROOT/tests/kotlin/sqlite_task_typecheck/android/database/sqlite/SQLiteStubs.kt" \
  "$ROOT/tests/kotlin/sqlite_task_typecheck/com/iipauk/spider/SpiderAuditRedactorStub.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderAgentModels.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderTaskCoordinator.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/FileSpiderTaskRepository.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SqliteSpiderTaskRepository.kt" \
  -d "$TMP_DIR/sqlite-task-repository-typecheck.jar"
echo "SQLite task repository Kotlin type-check PASS (signature stubs only; not Android Gradle compilation)."

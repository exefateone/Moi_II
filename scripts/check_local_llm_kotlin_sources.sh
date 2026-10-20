#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC_BIN="${KOTLINC:-$(command -v kotlinc || true)}"
if [[ -z "$KOTLINC_BIN" ]]; then
  echo "SKIP: kotlinc is unavailable; native source type-check skipped." >&2
  exit 0
fi
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
"$KOTLINC_BIN" \
  "$ROOT/tests/kotlin/stubs/AndroidLocalLlmApiStubs.kt" \
  "$ROOT/tests/kotlin/stubs/android/database/Cursor.kt" \
  "$ROOT/tests/kotlin/stubs/android/net/Uri.kt" \
  "$ROOT/tests/kotlin/stubs/android/os/StatFs.kt" \
  "$ROOT/tests/kotlin/stubs/android/provider/OpenableColumns.kt" \
  "$ROOT/tests/kotlin/stubs/android/app/ActivityManager.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderLocalModelStore.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderLocalLlmRuntime.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderLlmMode.kt" \
  -d "$TMP_DIR/local-llm-source-check.jar"
echo "Local LLM Kotlin source type-check PASS against signature-only Android API stubs (not an Android SDK build)."

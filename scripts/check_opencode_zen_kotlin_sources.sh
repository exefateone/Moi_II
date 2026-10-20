#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC_BIN="${KOTLINC:-$(command -v kotlinc || true)}"
JAVA_BIN="$(command -v java || true)"
if [[ -z "$KOTLINC_BIN" || -z "$JAVA_BIN" ]]; then
  echo 'SKIP: kotlinc/java unavailable; OpenCode Zen source smoke skipped.' >&2
  exit 0
fi
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
"$KOTLINC_BIN" \
  "$ROOT/tests/kotlin/opencode-stubs/org/json/JSONObject.kt" \
  "$ROOT/tests/kotlin/opencode-stubs/org/json/JSONArray.kt" \
  "$ROOT/tests/kotlin/opencode-stubs/com/iipauk/spider/SpiderTaskPlanner.kt" \
  "$ROOT/tests/kotlin/opencode-stubs/com/iipauk/spider/SpiderLearningHint.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderAgentModels.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderPlanParser.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderOpenCodeZenConfig.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderLearningContextCodec.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderOpenCodeZenClient.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/OpenCodeZenSpiderTaskPlanner.kt" \
  "$ROOT/tests/kotlin/SpiderOpenCodeZenSmoke.kt" \
  -include-runtime -d "$TMP_DIR/opencode-zen-smoke.jar"
"$JAVA_BIN" -jar "$TMP_DIR/opencode-zen-smoke.jar"
echo 'NOTE: compile uses signature-only org.json stubs; this is not a full Android SDK build or live OpenCode Zen request.'

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC_BIN="${KOTLINC:-$(command -v kotlinc || true)}"
if [[ -z "$KOTLINC_BIN" ]]; then
  echo "SKIP: kotlinc is unavailable; run this smoke test in an environment with Kotlin/JVM installed." >&2
  exit 0
fi
JAVA_BIN="$(command -v java || true)"
if [[ -z "$JAVA_BIN" ]]; then
  echo "ERROR: Java runtime is required to run the compiled smoke test." >&2
  exit 2
fi
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
"$KOTLINC_BIN" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderAgentModels.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderTaskCoordinator.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderSemanticSearch.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderLearningMemory.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderSkillLibrary.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderEmbeddingIndex.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/HttpSpiderEmbeddingClient.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderLearningContextCodec.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/HttpSpiderTaskPlanner.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/HttpSpiderChatClient.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderPlanParser.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/LocalSpiderTaskPlanner.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderLlmMode.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderOpenCodeZenConfig.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderOpenCodeZenClient.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderWorkspaceEdit.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderPolicyEngine.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderToolBroker.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderAuditLog.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderRequestLedger.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/FileSpiderTaskRepository.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/WorkspaceListingEvidence.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/WorkspaceFileListHandler.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/WorkspaceListingResultVerifier.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/WorkspaceTextTools.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/WorkspaceProjectCheck.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderCapabilityCatalog.kt" \
  "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt" \
  "$ROOT/tests/kotlin/stubs/android/provider/Settings.kt" \
  "$ROOT/tests/kotlin/stubs/AndroidContextStub.kt" \
  "$ROOT/tests/kotlin/stubs/AndroidRuntimeDependencyStubs.kt" \
  "$ROOT/tests/kotlin/stubs/LocalLlmRuntimeStub.kt" \
  "$ROOT/tests/kotlin/stubs/JSONObjectStub.kt" \
  "$ROOT/tests/kotlin/stubs/JSONArrayStub.kt" \
  "$ROOT/tests/kotlin/SpiderPersistenceSmoke.kt" \
  "$ROOT/tests/kotlin/WorkspaceTextToolsSmoke.kt" \
  "$ROOT/tests/kotlin/WorkspaceEditSmoke.kt" \
  -include-runtime -d "$TMP_DIR/spider-persistence-smoke.jar"
"$JAVA_BIN" -jar "$TMP_DIR/spider-persistence-smoke.jar"
echo "NOTE: JVM smoke uses a FileSpiderTaskRepository double for runtime wiring; SQLite task-history behavior is covered by Android instrumentation tests."

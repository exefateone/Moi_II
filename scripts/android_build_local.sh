#!/usr/bin/env bash
# Reproducible local Android build gate for ИИ Паук v0.38.
# This script fails closed when the Android toolchain is missing; it never treats
# static HTML/Python tests as a successful Android build.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android-project"

fail() { printf 'ANDROID BUILD BLOCKED: %s\n' "$*" >&2; exit 2; }

command -v java >/dev/null 2>&1 || fail "Java is not installed (JDK 17 required)."
if [[ -x "$ANDROID_DIR/gradlew" ]]; then
  GRADLE_CMD=("$ANDROID_DIR/gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=(gradle)
else
  fail "Gradle is unavailable: install Gradle 8.11.1 or add a verified Gradle Wrapper to android-project/gradlew."
fi

JAVA_VERSION="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
[[ "$JAVA_VERSION" == "17" ]] || fail "Detected Java major version '${JAVA_VERSION:-unknown}'; this project requires JDK 17 for its documented build gate."

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK_ROOT" ]] || fail "Set ANDROID_SDK_ROOT (or ANDROID_HOME) to an installed Android SDK."
[[ -d "$SDK_ROOT/platforms/android-35" ]] || fail "Android SDK Platform 35 is missing under $SDK_ROOT."
[[ -d "$SDK_ROOT/build-tools/35.0.0" ]] || fail "Android Build Tools 35.0.0 are missing under $SDK_ROOT."

cd "$ANDROID_DIR"
printf '\n== Gradle version ==\n'
GRADLE_VERSION="$("${GRADLE_CMD[@]}" --version | sed -n 's/^Gradle \([0-9.]*\).*/\1/p' | head -1)"
[[ "$GRADLE_VERSION" == "8.11.1" ]] || fail "Detected Gradle '${GRADLE_VERSION:-unknown}'; expected 8.11.1."
"${GRADLE_CMD[@]}" --version
printf '\n== Android unit tests ==\n'
"${GRADLE_CMD[@]}" --no-daemon testDebugUnitTest
printf '\n== Assemble debug APK ==\n'
"${GRADLE_CMD[@]}" --no-daemon assembleDebug

APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || fail "Gradle returned success but expected APK was not found at $APK."
printf '\n== APK artifact ==\n'
sha256sum "$APK"
printf 'APK build gate: PASS (debug/test build only; device QA and production signing remain separate gates).\n'

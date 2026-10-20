#!/usr/bin/env bash
set -euo pipefail

# Optional, explicit 491 MB smoke model download. Model weights are never fetched during app startup/build.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${1:-$ROOT/llm/models/qwen2.5-0.5b-instruct-q4_k_m.gguf}"
URL="https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
EXPECTED_SHA256="74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"

command -v curl >/dev/null 2>&1 || { echo "ERROR: curl is required." >&2; exit 2; }
if command -v sha256sum >/dev/null 2>&1; then
  hash_file() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  hash_file() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  echo "ERROR: sha256sum or shasum is required to verify model integrity." >&2
  exit 2
fi

mkdir -p "$(dirname "$TARGET")"
TARGET="$(cd "$(dirname "$TARGET")" && pwd)/$(basename "$TARGET")"
PARTIAL="$TARGET.partial"
if [[ -f "$TARGET" ]]; then
  actual="$(hash_file "$TARGET")"
  if [[ "$actual" == "$EXPECTED_SHA256" ]]; then
    echo "Model already downloaded and SHA-256 verified: $TARGET"
    exit 0
  fi
  echo "ERROR: target exists but its SHA-256 is wrong: $TARGET" >&2
  exit 3
fi

free_kib="$(df -Pk "$(dirname "$TARGET")" | awk 'NR==2 {print $4}')"
if [[ -z "$free_kib" || "$free_kib" -lt 650000 ]]; then
  echo "ERROR: at least 650 MiB of free space is recommended for the 491 MB model and temporary file." >&2
  exit 4
fi

echo "Downloading optional Qwen2.5-0.5B-Instruct GGUF (about 491 MB)."
echo "Destination: $TARGET"
echo "This small model is for local smoke tests; response quality is limited compared with larger models."
# Resume interrupted transfers. Keep .partial after network failure; remove it if the integrity check fails.
curl --fail --location --retry 3 --retry-all-errors --connect-timeout 20 --max-time 0 --continue-at - --output "$PARTIAL" "$URL"
actual="$(hash_file "$PARTIAL")"
if [[ "$actual" != "$EXPECTED_SHA256" ]]; then
  rm -f "$PARTIAL"
  echo "ERROR: SHA-256 mismatch. Partial file removed; no model was installed." >&2
  echo "Expected: $EXPECTED_SHA256" >&2
  echo "Actual:   $actual" >&2
  exit 5
fi
mv "$PARTIAL" "$TARGET"
echo "OK: downloaded and SHA-256 verified."
echo "Next: copy the file to your phone (or share it with Android), then open ИИ Паук → Local LLM → Import GGUF."

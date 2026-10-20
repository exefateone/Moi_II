#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="${1:-$ROOT/runtime/linux/linux-runtime-manifest.template.json}"
ARCHIVE="${2:-}"
ABI="${3:-}"
printf '%s\n' 'Spider Native · Linux runtime release gate'
printf '%s\n' 'This script validates a locally built, app-specific runtime bundle.'
printf '%s\n' 'It does not download or install packages, does not use root, and does not alter the phone.'
ARGS=(--manifest "$MANIFEST" --expected-app-id com.iipauk.spider)
if [[ -n "$ARCHIVE" ]]; then ARGS+=(--archive "$ARCHIVE"); fi
if [[ -n "$ABI" ]]; then ARGS+=(--target-abi "$ABI"); fi
exec python3 "$ROOT/scripts/validate_runtime_bundle.py" "${ARGS[@]}"

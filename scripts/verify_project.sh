#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
python3 -m unittest discover -s "$ROOT/tests" -p 'test_*.py' -v
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re, subprocess, sys, tempfile
root=Path(sys.argv[1])
html=(root/'app-web/ii-pauk.html').read_text(encoding='utf-8')
assert 'name="ii-pauk-version" content="0.39.3"' in html, 'Wrong visible HTML release marker'
ids=re.findall(r'\bid=["\']([^"\']+)', html)
assert len(ids)==len(set(ids)), f'duplicate HTML IDs: {len(ids)-len(set(ids))}'
scripts=[m.group(1) for m in re.finditer(r'<script\b[^>]*>(.*?)</script\s*>', html, re.S|re.I) if m.group(1).strip()]
with tempfile.TemporaryDirectory() as d:
  for i,body in enumerate(scripts):
    p=Path(d)/f'inline-{i}.js';p.write_text(body,encoding='utf-8');subprocess.run(['node','--check',str(p)],check=True)
print(f'HTML check PASS: {len(ids)} unique IDs, {len(scripts)} inline scripts syntax-clean')
assert (root/'android-project/app/src/main/assets/web/ii-pauk.html').read_bytes() == (root/'app-web/ii-pauk.html').read_bytes(), 'Android packaged HTML is stale'
print('Native asset sync PASS')
print('NOTE: this script does not compile Kotlin or build an APK; use Android Studio / Gradle.')
PY
if python3 -c 'import playwright.sync_api' >/dev/null 2>&1 && { command -v chromium >/dev/null 2>&1 || command -v chromium-browser >/dev/null 2>&1 || command -v google-chrome >/dev/null 2>&1; }; then
  echo 'Running optional Chromium UI smoke test...'
  python3 "$ROOT/scripts/smoke_runtime_ui.py"
else
  echo 'Browser smoke test: SKIPPED (optional dependencies Playwright/Chromium not available).'
fi
python3 "$ROOT/scripts/validate_runtime_bundle.py" --manifest "$ROOT/runtime/linux/linux-runtime-manifest.template.json" --expected-app-id com.iipauk.spider > /tmp/spider-runtime-gate.txt || code=$?
if [[ "${code:-0}" -eq 3 ]]; then
  echo 'Linux runtime release gate: NOT READY (expected: runtime template is not_built).'
elif [[ "${code:-0}" -ne 0 ]]; then
  cat /tmp/spider-runtime-gate.txt; exit "${code}"
else
  cat /tmp/spider-runtime-gate.txt
fi

if command -v kotlinc >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  echo 'Running pure Kotlin Agent Core persistence smoke tests...'
  bash "$ROOT/scripts/test_agent_core_jvm.sh"
  echo 'Running pure Kotlin Spider Media URL policy smoke tests...'
  bash "$ROOT/scripts/test_media_url_policy_jvm.sh"
  echo 'Running pure Kotlin OpenCode Zen direct API smoke tests...'
  bash "$ROOT/scripts/check_opencode_zen_kotlin_sources.sh"
else
  echo 'Agent Core JVM smoke: SKIPPED (Kotlin/JVM not available).'
fi

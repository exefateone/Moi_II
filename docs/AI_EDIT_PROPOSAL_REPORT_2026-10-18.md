# Change log — AI-proposed single-file edits

**Checkpoint date:** 18 October 2026
**Source version:** `0.39.0-ai-edit-proposal`

## Delivered in source

- Added a transient edit-source snapshot and shared model-response parser. Provider responses are treated as data, require `status: ok`, a non-empty complete replacement body, a bounded response and no disallowed control characters.
- Added provider-specific proposal generation for the already-supported local GGUF, Yandex AI Studio backend, OpenCode Zen, and explicit local-then-cloud fallback mode. Both the overall task goal and the concrete plan-step title are included so multi-step plans target the intended edit. A model never gets a filesystem handle or a write function.
- Added authenticated backend `POST /api/agent/edit`. It validates a relative path and bounded source text, rejects common secret-looking material, shares provider timeout/concurrency protections and uses a separate route quota. It returns proposal text only.
- Added native path selection, cloud-transfer consent when applicable, candidate preview, one-time write approval and separate overwrite checkbox for an existing file.
- Added SHA-256 source-version protection to the write request. The handler rejects stale previews and a second check occurs immediately before atomic replacement. New-file creation uses the `MISSING` sentinel. Existing independent read-back and SHA-256 verification remains in place.
- Bumped Android source version metadata to `versionCode 39` / `0.39.0-ai-edit-proposal`.

## Explicit limits

- Small source files only: up to 1,900 Unicode characters. Supported text path validation still applies.
- The proposal contains the full new file body. This is not a multi-file unified-diff patcher and does not merge concurrent edits.
- Existing file contents that match common secret patterns are not sent for edit proposals. Pattern scanning is a safety filter, not a guarantee that all confidential data can be detected.
- Hosted provider modes require explicit consent to transmit the goal/step, path and source body. Local-only mode does not need cloud-transfer consent.
- A successful file write is not proof that the project compiles. The edit flow does not run project tests or build an APK.
- Atomic replacement is guarded by source hashes but the filesystem does not provide a universal compare-and-swap transaction; the handler minimizes but cannot mathematically eliminate every external race between final check and move.

## Verification outcome

- `python3 -m unittest discover -s tests -p 'test_*.py'`: **111/111 passed**.
- `python3 -m py_compile backend/app/main.py scripts/*.py tests/*.py`: passed.
- `bash -n scripts/*.sh`: passed.
- `python3 scripts/smoke_runtime_ui.py`: **8/8 browser scenarios passed**, zero uncaught page errors. Native WebView/PTTY/device integration is not tested by this browser smoke.
- Agent Core JVM harness was freshly rerun and passed all 15 grouped scenarios, including strict edit parsing, complete-diff preview limits and stale-source write guards. The harness produced exit code 0.
- Android Gradle build, unit/instrumentation tests, NDK build, APK, real device behavior and live authenticated provider requests are **not verified**. No APK is included.

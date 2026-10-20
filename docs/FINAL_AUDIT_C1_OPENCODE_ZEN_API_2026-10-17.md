> Historical checkpoint notice: this audit predates the later 17 Oct checkpoint that adds SEARCH_WORKSPACE_CONTENT, READ_WORKSPACE_FILE and WRITE_WORKSPACE_FILE. Use `docs/CHANGELOG_C1_REAL_WORKSPACE_TOOLS_LEARNING.md` and the current `SpiderCapabilityCatalog` for active tool capabilities.

# Final audit — OpenCode Zen direct API (17 October 2026)

## Decision / product scope

The requested integration is **OpenCode Zen hosted model API**, not the OpenCode Server coding-agent API. A phone can send requests directly to the hosted HTTPS API; no local computer, self-hosted OpenCode process, VPS or separate Spider backend is required for chat inference.

OpenCode Zen provides model inference only; it does not automatically grant repository access, file editing or shell execution. The older separate server connector described in this historical audit has been removed from the current source tree.

## Implementation in this checkpoint

- Added a native Android settings screen for the OpenCode Zen API key and supported model selection.
- Added a direct HTTPS client pinned to the official `opencode.ai/zen/v1` host, with a public model catalog request and Chat Completions requests. Arbitrary API host entry is not available.
- The integration allows only curated model IDs documented as Chat Completions compatible. Responses-only and Anthropic Messages-only IDs are rejected by this connector rather than sent to the wrong protocol endpoint.
- Added a real one-output-token inference test before saving settings, because a public catalog response alone does not prove that the key/model combination is authorized. The UI warns that this can count toward provider usage or billing.
- The key and model ID are encrypted using AES-GCM with the encryption key held in Android Keystore. Redirects are rejected; HTTPS is mandatory; request sizes, response sizes and timeouts are bounded. `allowBackup=false` and cleartext traffic is disabled in the Android manifest.
- Added the provider to model-mode selection and hooked it into direct chat responses. It can also propose typed agent plans, which are still parsed and restricted by the existing allow-listed tool broker; the only current executable agent tool remains `LIST_WORKSPACE_FILES`.
- Updated the capability catalogue, UI labels, README, architecture notes and regression checks to distinguish model inference from local Agent Broker actions.

## Verification performed

- Python bytecode/syntax check: passed.
- Python test suite: **102/102 passed**.
- Agent Core JVM smoke suite: **14 scenarios passed**.
- Spider Media URL Policy JVM smoke: **15 assertions passed**.
- OpenCode Zen Kotlin source smoke: passed for API-key/model validation, fixed HTTPS endpoints, and protocol-specific catalog filtering. This compile uses signature-only `org.json` stubs; it is not a real Android build or a live request.
- Historical checkpoint: connector source smoke passed before the connector was removed.
- HTML/JS check: **132 unique IDs**, inline-script syntax clean, web asset byte-identical to the Android packaged copy.
- Chromium UI smoke: all **8 named scenarios passed**, with no uncaught page errors. This tests the HTML prototype, not Android WebView/device integration.
- `bash -n` check for the updated project verification script: passed.

The aggregate `scripts/verify_project.sh` did **not** complete within the available command time budget. The listed relevant suites were run separately and passed; do not represent the aggregate run as fully completed.

## Remaining release gates

- Android SDK/Gradle/NDK are unavailable in this environment; the APK was not built and no Android unit/instrumentation/device test was run.
- No user API key was available and no live authenticated OpenCode Zen inference request was performed here.
- The settings flow still needs on-device verification for Android Keystore, key persistence, model catalogue UI and error display. A live API test from the settings screen is the first acceptance step once the APK is built and the user supplies their own key.
- API key storage on-device does not make prompt data private from the provider: once this model mode is selected, the app sends the user's chosen chat content and planner goal to OpenCode Zen.
- The current release intentionally keeps only the direct hosted model API; project operations remain local to Agent Broker.


## Follow-up delta — unified learning memory across provider routes (17 Oct 2026)

- Introduced `SpiderLearningContextCodec`, a common payload filter used by hosted chat/planning paths so they all apply the same five-hint cap, allowed outcome kinds, single implemented tool ID, sanitized error codes and confidence clamping. Local topic tokens are used only for retrieval and are not included in hosted metadata.
- OpenCode Zen task planning now injects relevant learning context; its previous planner path did not. OpenCode Zen chat now passes relevant history hints too. Existing local GGUF and Yandex chat/planning routes continue to use the same durable SQLite experience memory.
- Task memory is based on terminal outcomes and independent verification; explicit 👍/👎 ratings adjust retrieval confidence. The hosted model or imported GGUF does not have its weights updated. Ordinary chat messages alone do not automatically become persistent experiences.
- Targeted validation after this delta: 102 Python tests passed; 14 Agent Core JVM smoke groups passed; 15 Media URL policy assertions passed; OpenCode Zen Kotlin smoke passed including metadata boundary/planner propagation; local LLM source typecheck, historical connector smoke (pre-removal), and SQLite task repository source typecheck passed. A separate Chromium smoke call did not finish within its command time budget and is not claimed.
- Still pending: Android Gradle build, instrumented/device tests, APK assembly, live OpenCode Zen/Yandex API calls and a measured benchmark demonstrating quality improvement with memory enabled versus disabled.

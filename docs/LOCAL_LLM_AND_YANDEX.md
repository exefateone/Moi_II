# Local core, GGUF and Yandex AI Studio — Stage C1

**Source checkpoint:** 16 October 2026, deep-audit corrections. **Runtime status:** not yet Android-build/device/live-provider verified.

## Intended routing

1. `LOCAL_ONLY` is the default and must not call the network. Local inference is available only when the JNI library is built and the user has loaded a valid `.gguf` model.
2. `YANDEX_CLOUD` explicitly sends the selected chat/task request through the authenticated Spider backend to Yandex AI Studio (or the configured generic provider).
3. `LOCAL_THEN_YANDEX` explicitly authorizes fallback. Recent user/assistant messages may leave the device only in this chosen mode. Provider keys are server-side only.

`SpiderAgentRuntime` remains the application orchestration core: policy, task state, approval, audit, verification and SQLite experience memory are not delegated to Yandex. The LLM only generates text or proposes a typed plan; it does not acquire tools or execute code. Remote and local planner responses share a strict parser that accepts only the five registered workspace tools: `LIST_WORKSPACE_FILES`, `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, `WRITE_WORKSPACE_FILE`, and `CHECK_WORKSPACE_PROJECT` (bounded static checks only). Actual tool effects remain in the app-side broker with explicit approval and independent verification.

## Local model storage and runtime

- Android CMake uses `FetchContent` with upstream `ggml-org/llama.cpp` pinned to v0.6.0. The JNI bridge uses the upstream GGUF loading, chat-template, tokenize/decode and sampler APIs. Build targets are `arm64-v8a` and `x86_64` and the initial path is CPU-first.
- No model weights are committed. The in-app picker copies a user-selected `.gguf` to the app-specific `llm` directory, validates the extension, GGUF magic bytes and available SAF size metadata, checks remaining free space before and during copying, and uses a bounded fingerprint to detect obvious file changes. Existing mapped weights are unloaded before a replacement can overwrite their file. A RAM check reduces the chance of a device OOM but is only conservative guidance—not a guarantee that the model will fit.
- Model load/inference is performed on a worker thread; context is limited to 512–4096 tokens and generation to 512 tokens. Full paths are omitted from native logs. If JNI is absent, the UI reports the engine as not built.

## Yandex adapter

Backend configuration uses `PROVIDER_KIND=yandex_ai_studio`, `YANDEX_API_KEY`, `YANDEX_FOLDER_ID`, and `YANDEX_MODEL`. The backend fixes the base URL to `https://ai.api.cloud.yandex.net/v1`, constructs `gpt://<folder>/<model>`, and sends `Authorization: Api-Key`, `OpenAI-Project`, and `x-data-logging-enabled: false`. Model/folder values are syntax-validated and user-supplied hosts are not accepted in Yandex mode. Generic OpenAI-compatible providers remain supported under a separate mode.

The Android stores only the backend's app-client token encrypted with Android Keystore. The Yandex API key stays server-side. `/api/chat` and `/api/agent/plan` require Bearer authentication. `/api/agent/step` stays disabled.

## What “self-learning” means

The SQLite episode store captures verified task outcomes and explicit user feedback, retrieves relevant scoped evidence, and exposes bounded hints to later turns. This is on-device behavioral adaptation. It does **not** update a local model's weights or perform online fine-tuning. Chat messages are held in the current activity session; the learning store does not absorb full transcripts by default. Cloud planning/chat may receive a small typed outcome context; topic tokens and raw episode records are excluded from cloud payloads.

## Required acceptance gates

- Python/source contracts: **87/87 passed across the five test modules when run separately**. HTML static validation reports 130 unique IDs and Android HTML asset sync passes. The latest full `verify_project.sh` run did not complete its optional Chromium phase: the Playwright runner ended with timeout/EPIPE. Do not count that as a current Chromium pass. An isolated source check for local-LLM Kotlin passes against signature-only Android API stubs, which is not an Android SDK/Gradle build. JVM Agent Core smoke did not finish within the latest audit window.
- Android Gradle/CMake build completes on CI for both ABIs, with dependency pin integrity and no missing JNI exports. A new instrumentation test checks that the native library loads and does not claim a GGUF model is already loaded.
- Device tests import a real small GGUF, load/generate a short answer, unload/reload, handle unsupported chat templates and low-memory failure, and verify the local-only mode makes no network request.
- Backend tests pass for authentication, valid/invalid Yandex configuration, folder/model validation, safe response parsing and redacted errors.
- On a separately configured deployment, run one live Yandex `/api/chat` and `/api/agent/plan` request. Current mock tests are not live-provider proof. The currently documented official Completions API requires a service account with `ai.languageModels.user`, an API key with `yc.ai.foundationModels.execute`, and a folder ID; see the [official Yandex AI Studio request guide](https://aistudio.yandex.ru/ru/docs/ai-studio/operations/generation/completions-basic).
- Verify that local mode remains usable when the network is disabled, and that cloud fallback occurs only in `LOCAL_THEN_YANDEX`.
- Build/install APK and verify the LLM screen, model import, task planner routing and existing UI on actual Android devices.


## Deep-audit corrections (16 October 2026)

- Cloud chat now sizes the **serialized UTF-8 JSON bytes**, not only Kotlin character counts, trims oldest turns until the request is within a 14,000-byte client budget (below the backend's 16 KiB ceiling), and always preserves the newest turn or fails before sending if that one turn cannot fit.
- Chat transport/model errors are UI errors, not synthetic assistant messages. On a failed send, the unsent user turn is removed from history and restored to the editor when the user has not started another draft.
- Chat mode is captured at send time. Changing the UI selection while a request is running cannot silently reroute an already submitted `LOCAL_ONLY` turn to Yandex. Cloud fallback remains possible only when `LOCAL_THEN_YANDEX` was selected for that send.
- The local task planner now requests 512 generated tokens, matching the native runtime's actual hard maximum instead of relying on silent clamping of a 640-token request.
- JNI generation now checks batch-output setup and sampler/token initialization failures and reports a controlled error instead of passing invalid native pointers onward. UTF-8/UTF-16 conversion now validates multibyte sequences and replaces invalid UTF-8/unpaired Java surrogates rather than returning mojibake for truncated model output.
- Added a mocked Android instrumentation test using multibyte Russian text and emoji to verify request-byte bounds and preservation of the newest turn. This test is authored, but has not been run because the Android SDK/Gradle/emulator toolchain is unavailable in the audit environment.

These changes have source/contract evidence; they are not a substitute for the required Gradle/CMake build, live-device GGUF inference, or a real Yandex AI Studio request.

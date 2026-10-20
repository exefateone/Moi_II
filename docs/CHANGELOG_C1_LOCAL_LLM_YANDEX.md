# Stage C1 — local GGUF engine + Yandex AI Studio routing

**Checkpoint:** 16 October 2026 · product version remains **v0.38**. This is a source checkpoint, not a verified APK.

## Architecture now present in source

- The application owns the agent core: task lifecycle, policy, approval, audit, verification, SQLite task history, episodic memory and retrieval. A language model may respond or propose a typed plan, but never grants tools or executes an arbitrary shell command.
- Added Android CMake/JNI bindings to upstream `ggml-org/llama.cpp` pinned to `v0.6.0`, with CPU-first GGUF inference sources for `arm64-v8a` and `x86_64`.
- Added a **Local LLM** screen. It uses the Android document picker to import a user-owned `.gguf` file into the application-specific `llm` folder; checks extension, size and GGUF magic; preflights free storage from SAF size metadata and rechecks while copying; and offers explicit load/unload, local chat and model-routing controls. No large model weights are bundled in source control or the APK. Existing mapped native weights are unloaded before replacing a model file, and the previous model is reloaded after a failed import where possible.
- Added three explicit modes: `LOCAL_ONLY` (default; no remote fallback), `YANDEX_CLOUD` (authenticated backend), and `LOCAL_THEN_YANDEX` (remote call only when local inference fails and the user has explicitly selected this mode). The fallback constructs the remote planner lazily, so local inference does not require Yandex credentials.
- Local and remote task plans use one strict parser. Only the currently implemented `LIST_WORKSPACE_FILES` tool is accepted, and every action still passes through the existing policy, one-time confirmation and independent verification flow.
- Added authenticated `/api/chat` to the backend and a first-class Yandex AI Studio adapter for both chat and planning. Yandex configuration stays server-side; Android stores only the separate backend client token encrypted with Android Keystore. Optional embeddings accept documented hierarchical `emb://<folder>/<model>/<version>` URIs and remain opt-in.
- A user-visible explanation now makes the meaning of “self-learning” explicit: verified task outcomes and feedback are saved in local SQLite and retrieved as bounded context. This is behavioral adaptation; it does **not** automatically retrain GGUF weights or upload full chat transcripts to memory.
- Local-then-Yandex fallback is lazy and explicit. An unavailable native library or missing model is surfaced as such; the app does not synthesize fake answers or mark the engine available before JNI loads.
- CI now provisions a pinned NDK/CMake, runs Python/backend contract tests, runs Android unit tests and instrumentation on an API 35 emulator, builds the debug APK and uploads results. A native-library instrumentation test was added. The empty GitHub repository in the current session has not been populated, so this workflow has not run.

## Verification performed in this workspace

- `python -m unittest discover -s tests -p 'test_*.py'`: **86/86 passed**. Mocked tests include Yandex chat, planning, embedding URI validation, authorization/header checks, secret-safe output, and the local-route/fallback source contracts.
- `node --check` on inline JavaScript and source HTML contract check: **130 unique HTML IDs**, syntax valid.
- Packaged Android HTML equals `app-web/ii-pauk.html` byte for byte.
- Chromium UI smoke: **8/8 scenarios passed**, no uncaught page errors.
- `scripts/test_agent_core_jvm.sh`: **13/13 grouped scenarios passed**. This JVM harness uses explicit test doubles for Android-only SQLite and native runtime; it is not native Android compilation.
- `scripts/test_media_url_policy_jvm.sh`: **15/15 assertions passed**.
- `scripts/check_local_llm_kotlin_sources.sh`: focused Kotlin source type-check passed against signature-only Android API stubs, including the storage preflight and model-store change. This is not a real Android SDK build.
- Linux runtime release validator remains correctly `NOT READY` because its manifest is a `not_built` template.
- `scripts/verify_project.sh` was attempted again: HTML checks, asset sync, Chromium UI smoke and the Python tests completed, and the runtime gate correctly reported `NOT READY`; the aggregate later exceeded this environment's execution limit. Agent Core and the focused local-model Kotlin checks were run independently and passed.

## Not yet verified — important release gates

- No Gradle/Android SDK/NDK toolchain is installed here; no Android Gradle build, CMake compilation or APK was produced.
- Native inference has not yet loaded a real GGUF on a physical device or emulator. Model memory/RAM checks are estimates, not guarantees.
- Yandex API tests are mocked. Real credentials, live backend deployment, quota behavior and end-to-end provider requests have not been tested.
- The GitHub repository is currently empty, and CI has not run on GitHub.
- The agent still has only the read-only `LIST_WORKSPACE_FILES` execution handler; file editing, generic command execution, a real PTY/Linux runtime and broad phone automation are not yet implemented.

## Yandex deployment configuration

Set secrets on the backend host, not in the Android app or repository:

```dotenv
SPIDER_API_TOKEN=<random-secret-at-least-24-characters>
PROVIDER_KIND=yandex_ai_studio
YANDEX_API_KEY=<service-account-api-key>
YANDEX_FOLDER_ID=<Yandex-Cloud-folder-id>
YANDEX_MODEL=yandexgpt-5.1
```

The backend uses `https://ai.api.cloud.yandex.net/v1`, `Authorization: Api-Key`, `OpenAI-Project`, and `x-data-logging-enabled: false`. Configure the Android app with the backend HTTPS endpoint ending `/api/agent/plan` and the separate `SPIDER_API_TOKEN`; never paste the Yandex provider API key into the app. The model API requires a service account with `ai.languageModels.user` and API key scope `yc.ai.foundationModels.execute` according to Yandex AI Studio documentation.

## Next sequence

1. Push the completed source tree to the GitHub repository so the configured CI can run.
2. Resolve the first real CMake/Gradle/emulator build feedback, and produce a debug APK only after the workflow is green.
3. Load a small supported GGUF fixture or user-provided model on an Android device; test local-only offline inference, unload/reload, unsupported chat templates, low RAM and long context.
4. Configure backend secrets and run a live Yandex chat and plan request; verify no provider key in APK/logs and that cloud fallback occurs only in the selected mode.
5. Continue the memory verification and skill-library SQLite migration before widening the agent tool allow-list.

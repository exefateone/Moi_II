# Spider Agent Planner and Chat API — v0.39.3

The backend provides authenticated, bounded chat and planning for ИИ Паук. It supports Yandex AI Studio directly and retains a generic OpenAI-compatible provider option. It never executes Android tools, shell commands or model-generated functions. Planner output is typed and restricted to the five tool IDs actually registered in the Android Agent Broker: `LIST_WORKSPACE_FILES`, `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, `WRITE_WORKSPACE_FILE` and `CHECK_WORKSPACE_PROJECT` (bounded static JSON/XML/UTF-8 checks only; no code execution). The Android app alone executes those tools inside a selected app-private workspace, after per-action confirmation and independent verification. Unsupported goals are rejected instead of receiving a fake plan.

## Configure Yandex AI Studio (recommended)

Set these values in the deployment platform's secret/configuration manager; never commit real values:

- `SPIDER_API_TOKEN`: long random client bearer token (24+ characters) used only between the Android app and your backend. The Web UI asks for this token only into session memory; the native Android planner stores its token with Android Keystore.
- `PROVIDER_KIND=yandex_ai_studio`
- `YANDEX_API_KEY`: API key for a Yandex Cloud service account with the `ai.languageModels.user` role / `yc.ai.foundationModels.execute` scope.
- `YANDEX_FOLDER_ID`: the folder ID associated with that service account and model.
- `YANDEX_MODEL=yandexgpt-5.1` (or a model ID enabled in the same folder). The backend constructs `gpt://<folder-id>/<model-id>`.
- `PROVIDER_TIMEOUT_SECONDS`: optional timeout, clamped to 3–25 seconds (default 18).
- `SPIDER_PROVIDER_RATE_LIMIT_PER_MINUTE`: optional per-route request limit per token, 1–600 per rolling 60-second window (default 20).
- `SPIDER_CORS_ORIGINS`: comma-separated explicit browser/WebView origins (default `https://appassets.androidplatform.net`). Add the exact origin used by a hosted web frontend; wildcards and URL paths are rejected. CORS is not authentication; the bearer token remains required.
- `YANDEX_EMBEDDING_MODEL`: optional; leave blank unless you have chosen and tested a compatible Yandex embedding URI. Cloud embeddings remain separately opt-in in the Android app.

The backend uses the fixed HTTPS origin `https://ai.api.cloud.yandex.net/v1`, headers `Authorization: Api-Key ...` and `OpenAI-Project: <folder-id>`, and `x-data-logging-enabled: false`. Do not enter the Yandex provider key in Android or HTML. For the HTML chat panel, enter the backend base URL (for example `https://<your-host>`), without appending `/api/chat` or `/api/agent/plan`, plus `SPIDER_API_TOKEN`. The native Agent screen has a separate planner setting and expects the full `/api/agent/plan` route. The HTML token stays only in current WebView memory and must be re-entered after reload; the native token is encrypted by Android Keystore.

The backend no longer contains a special OpenCode provider alias. The generic compatible-provider mode requires HTTPS and uses the same server-side `PROVIDER_API_KEY`, `PROVIDER_BASE_URL`, and `PROVIDER_MODEL` settings regardless of vendor. The Android app's direct OpenCode Zen connection is configured separately in the app and does not use this backend route.

## Generic OpenAI-compatible provider (alternative)

Set `PROVIDER_KIND=openai_compatible`, `PROVIDER_API_KEY`, `PROVIDER_BASE_URL` (HTTPS base such as `https://api.openai.com/v1`), and `PROVIDER_MODEL`. Optionally set `PROVIDER_EMBEDDING_MODEL` for the embeddings route. These credentials are server-only.

## Routes

- `GET /api/health`: public diagnostic returning status/version/provider-configuration presence; the HTML connection checker does not use it as an authorization result.
- `GET /api/client/check`: verifies the client bearer token without making a billable model request, and reports provider-configuration status.
- `GET /api/models`: returns a configured model identifier without provider URL/key; the HTML uses this after authenticated client check.
- `POST /api/agent/plan`: accepts `Authorization: Bearer <SPIDER_API_TOKEN>` and `{ "task_id": "...", "goal": "...", "learning_context": [...] }`. Output is validated against the strict tool allow-list.
- `POST /api/agent/edit`: accepts an authenticated bounded task goal, concrete plan-step title, relative workspace path and source text (maximum 1,900 characters); returns a candidate complete file body only. The backend never reads or writes the device filesystem. The Android client asks for cloud-transfer consent before sending this content, previews the replacement, requires a one-time write approval and (for an existing file) a separate overwrite confirmation.
- `POST /api/chat`: authenticated cloud chat; accepts 1–12 bounded `{role,content}` messages and optional typed task outcome metadata. It never exposes raw internal history/topic tokens to the provider. Use this only when the user selects cloud mode or explicitly permits local-to-cloud fallback.
- `POST /api/agent/embeddings`: optional, separately disabled until a compatible embedding model is configured. It accepts sanitized topic-token strings only, not full prompts, file contents, URLs, credentials or reflections.
- `POST /api/agent/step`: stays disabled (503). Planning never implies permission to execute arbitrary actions.

## Local model path

Local inference is an Android-native llama.cpp runtime built by CMake, not by this backend. The app accepts user-supplied GGUF model files into its app-specific `llm` folder. No model weights are bundled in this repository. If the native library is unavailable or the model is not loaded, local mode reports that fact and does not fake a response.

## Run / security

Run locally with `uvicorn app.main:app --host 127.0.0.1 --port 8787` from this directory. The source now applies an in-process rolling-window quota of 20 requests/minute per route per configured bearer token by default, plus a four-request provider concurrency ceiling. HTTP 429 responses include `Retry-After`. The quota setting is validated and fails closed when invalid.

These controls are intentionally a last-resort application guard, not a production perimeter: state is process-local, resets on restart, and is not coordinated across workers or replicas. Before public exposure, configure TLS, a reverse-proxy/API-gateway rate limiter and spend quota, deployment-level monitoring/alerting, abuse protection, and real authenticated deployment. Source tests use mocked provider responses; they do not prove that real Yandex credentials work.

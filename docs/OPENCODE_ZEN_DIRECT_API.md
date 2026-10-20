# OpenCode Zen API in ИИ Паук — direct, no self-hosted OpenCode

## What this integration does

ИИ Паук can call the hosted OpenCode Zen model API directly from the Android device. This is the suitable route when the goal is to use OpenCode's hosted model API like a model-provider API (for example, a hosted Yandex AI Studio connection) without deploying an OpenCode process, a personal computer, a VPS, or a separate Spider backend for this provider.

Official references:

- OpenCode Zen: https://opencode.ai/docs/zen

OpenCode Zen API endpoints include `GET https://opencode.ai/zen/v1/models` and model-specific inference paths. This connector deliberately uses only the OpenAI-compatible Chat Completions endpoint `https://opencode.ai/zen/v1/chat/completions`. OpenCode Zen models can use different protocols (Responses, Anthropic Messages, or Chat Completions); this client filters the model catalog to a curated allow-list of Chat Completions model IDs rather than sending every model to the same endpoint.

## Configure on the phone

1. Create or open an OpenCode Zen account and create an API key in the account's official key flow: https://opencode.ai/auth.
2. In ИИ Паук, open **Модели и подключение / локальная LLM → Настроить OpenCode Zen API — без своего сервера**.
3. Paste the API key and choose one of the supported Chat Completions models.
4. Tap **Тестовый запрос и сохранить**. The client checks the public model catalog, then submits one minimal inference request capped at one output token because a public `GET /models` response alone cannot prove that the provided API key is valid.
5. Select **OpenCode Zen API — без своего сервера** in the language-model mode selector.

The test request is a real inference request; the provider may count it against usage or billing. The user can refresh the public catalog without sending a generation request.

## Security and data flow

- The endpoint is pinned in source to `https://opencode.ai/zen/v1`; the user cannot direct the API key to an arbitrary host.
- HTTPS is mandatory, redirects are rejected, and request/response sizes and timeouts are bounded.
- The key and model ID are encrypted using AES-GCM with an AES key held in Android Keystore. The key is not written to HTML/localStorage or to a user-supplied endpoint.
- When this mode is selected, chat messages and the task goal sent to its model planner leave the phone and go to OpenCode Zen. Do not send private information unless allowed by your provider/privacy requirements.
- Use account usage limits and revoke the key from the provider account if it is lost. Removing local settings does not revoke the key at the provider.

## Scope of the hosted model API

**OpenCode Zen API is a hosted language-model gateway.** It provides model inference; it does not grant project-file access or arbitrary tool execution.

OpenCode Zen provides model inference only. Access to project files and actions is handled separately by the app-local Agent Broker, with explicit per-action policy checks and confirmations.

Even when OpenCode Zen is selected for Agent planning, the app validates model output against `SpiderPlanParser` and can execute only registered workspace tools: `LIST_WORKSPACE_FILES`, `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, `WRITE_WORKSPACE_FILE`, and `CHECK_WORKSPACE_PROJECT`. Each step is subject to the confirmation required by its policy and independent verification. The hosted model does not grant file read/write, shell, browser automation or external-app permissions.

## Verification status

Source-level smoke tests cover model-ID validation, rejection of incompatible Responses-only IDs, fixed HTTPS endpoint constants and model-protocol filtering. The project has no Android SDK/Gradle/NDK in the current execution environment, so an APK build/device run and a live authenticated generation request with the user's own API key remain pending.

## Unified learning memory

OpenCode Zen chat and task planning now receive the same bounded, locally retrieved learning hints used by the Yandex backend route. The phone selects relevant episodes using local topic tokens; only outcome kind, the allowlisted tool ID, a safe error code and confidence score are sent to Zen as learning context. The separate, optional cloud-embeddings setting is off by default and targets the configured backend only after explicit opt-in. The durable experience memory is provider-independent and is updated by terminal task outcomes plus explicit user feedback. This is Spider-side memory-based adaptation, not provider model-weight training; ordinary chat transcripts are not automatically stored as long-term memories.

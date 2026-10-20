# Changelog — direct OpenCode Zen API provider

- Added a native Android settings screen for OpenCode Zen hosted model API keys and Chat Completions models.
- Added direct HTTPS requests to the pinned OpenCode Zen endpoints for the public model catalog and Chat Completions; no OpenCode server, VPS, personal computer or Spider backend is required for model inference.
- Added AES-GCM encrypted local credential storage backed by Android Keystore, explicit model selection, API-key/model test and local disconnect action.
- Added protocol-aware model allow-listing because OpenCode Zen publishes Responses, Anthropic Messages and Chat Completions endpoints; this integration does not treat all model IDs as Chat Completions-compatible.
- Added OpenCode Zen to chat routing and typed Agent planning. Agent plan output continues through the existing strict parser, and the broker's only executable tool remains `LIST_WORKSPACE_FILES`.
- Historical note: a separate server connector existed at this checkpoint. It has since been removed from the current project; the hosted OpenCode Zen model API remains.
- Added source-level smoke tests for API key/model validation and protocol filtering.
- No live authenticated API request or APK/device test was possible in this environment; service/account/model availability and Android Keystore behavior remain to be tested on a device.

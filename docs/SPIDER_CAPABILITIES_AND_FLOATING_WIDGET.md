# Spider floating widget and truthful capability guide — 17 Oct 2026

## Floating Spider

The Android widget is started only after the user explicitly enables it and grants Android's `SYSTEM_ALERT_WINDOW` permission. It uses `TYPE_APPLICATION_OVERLAY`, has a persistent notification, can be dragged and docked to the nearest horizontal edge, and retracts after inactivity while leaving a 24 dp strip visible. Touching the tucked bubble animates it back out and opens the quick panel. The panel contains routes into Chat, Browser, Media, Terminal, Agent and Code Studio, plus “К краю” and “Выключить виджет”. The notification also exposes a stop action.

The in-app spider uses a higher stacking order than app tabs and modal content. Its in-app quick panel is above the spider itself so the controls remain clickable; the panel is scrollable on short screens. After 45 seconds of idle time the spider slides partly beyond the app viewport while leaving 24 px visible; one tap brings it back. It does not tuck during an active confirmation, text entry, voice session, working state, open panel or visible modal.

These two behaviours are separate: the in-app spider is limited to the application WebView; the native overlay can appear over ordinary app windows when Android allows it. The OS can cover overlays on protected/system surfaces, and manufacturer background-service limits can affect persistence. Real device acceptance is still required.

The overlay is not a screen reader or a cross-app automation service. It does not inspect or tap other apps. Cross-app interaction needs a separately consented AccessibilityService, and screenshot reading requires explicit MediaProjection consent. Neither has been implemented or claimed by this change.

## Ability catalogue

`SpiderCapabilityCatalog` is dynamically injected into the local conversational prompt via `SpiderAgentRuntime`. The backend chat has matching constraints, and the local task planner expressly refuses to invent tool handlers. The in-app Spider quick menu includes “Возможности Паука”, which asks the active model for a truthful capability summary. The native Agent screen also has “Показать карту возможностей и ограничений Паука”, showing the runtime catalogue and state labels.

The catalogue labels current source/installation state rather than claiming success from source code alone. Saved cloud configuration is not a health-check; a requested overlay is not proof it is visible; source-only native code is not proof of a device-tested runtime.

The registered Agent Broker toolset in v0.39.1 has five actions in the selected app-private workspace: `LIST_WORKSPACE_FILES` (list names), `SEARCH_WORKSPACE_CONTENT` (bounded text search), `READ_WORKSPACE_FILE` (read one supported UTF-8 text file with credential-pattern redaction), and `WRITE_WORKSPACE_FILE` (atomically persist exact text entered by the user). Search/read/write each require explicit confirmation and independent verification; overwrite additionally needs a separate consent checkbox. The bounded `CHECK_WORKSPACE_PROJECT` tool validates JSON syntax, securely parses XML/SVG and checks UTF-8 readability for supported text files; it does not run code, shell, tests or builds. The separate AI edit path proposes a bounded replacement with a preview and explicit consent. The write tool itself does not autonomously draft a patch from model output. These are native task-screen tools and are not invoked directly from the separate web-shell assistant. The model is taught to distinguish these concrete tools from manual or unverified modules:

- **Spider Browser:** manual browsing/tabs; automatic reading of arbitrary pages is not connected to Agent Broker.
- **Spider Media:** local/HTTPS/HLS/DASH source code exists; playback and background behaviour still need Android device acceptance.
- **Spider Terminal:** interactive `/system/bin/sh` PTY for manual use under app UID/private storage; not a full Linux distribution or Agent Broker shell tool.
- **Code & Build:** isolated HTML/CSS/JavaScript preview and HTML export; APK build pipeline is not connected.
- **Floating widget:** permission-gated native overlay; not external-app control.
- **Local LLM:** needs a successfully built native engine and imported/loaded GGUF model.
- **Yandex cloud:** needs saved backend settings and a working deployed provider; configured settings do not prove availability.
- **Automatic file editing, deleting, arbitrary commands, autonomous downloads/installations, external-app clicks and APK build:** not currently connected as agent actions.

“Teach Spider” here means a runtime capability map, safety instruction in the local/cloud prompts, explicit unsupported-action handling and evidence-based episodic memory. It does not fine-tune model weights, magically enable unavailable handlers or grant Android permissions.

## Verification status

Source/contract checks confirm the overlay route, permissions, CSS stack order, edge tuck/reveal hooks, packaged-asset synchronisation and capability prompt wiring. Current workspace-tools checkpoint: Python suite 103/103; Agent Core JVM harness 15 grouped scenarios (including actual read/search/write); Media URL Policy JVM 15 assertions; Chromium smoke 8 scenarios; OpenCode/Zen/local-LLM source checks passed. SQLite typecheck uses signature stubs only. No Android Gradle build, APK, emulator test or physical-device test is claimed.

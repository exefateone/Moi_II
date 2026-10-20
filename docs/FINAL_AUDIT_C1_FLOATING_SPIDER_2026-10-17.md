> Historical checkpoint notice: this audit describes the capability map before the later 17 Oct checkpoint that registers three more workspace text tools. Use `docs/CHANGELOG_C1_REAL_WORKSPACE_TOOLS_LEARNING.md` and the current `SpiderCapabilityCatalog` for active Agent Broker capabilities.

# ИИ Паук v0.38 — floating Spider and capability map checkpoint

Date: 17 October 2026

## What changed

1. **Native always-on-top widget.** `SpiderOverlayService` remains opt-in and permission-gated. It snaps to a horizontal edge, retracts after inactivity while leaving a small touchable edge strip, returns on touch and opens quick actions. The ongoing notification can stop the widget. Cleanup cancels delayed actions/animation and removes window views.
2. **In-app spider layering and edge peek.** The app spider is above navigation and modal content; the action panel is above the spider and scrollable on short screens. It retracts after idle and crawls back when tapped. It deliberately delays retraction while a confirmation/dialog, voice input, text entry, panel, or work state is active.
3. **Capability map.** `SpiderCapabilityCatalog` enumerates current modules and labels them as ready within the app, permission/configuration-required, source-unverified, UI-only or not connected. Runtime flags for overlay permission/request, local engine/model and saved backend setup are read when the local conversation prompt is built.
4. **Model orientation.** The local conversational system prompt receives the capability map; cloud chat has matching capability restrictions; the local planner clarifies that screen navigation does not equal an Agent Broker execution tool. Self-learning is described as outcome-memory/retrieval, not model-weight fine-tuning.
5. **Visible map.** The app quick panel has “Возможности Паука”; the native Agent screen has “Показать карту возможностей и ограничений Паука”.
6. **Test maintenance.** The pure-JVM Agent Core harness now compiles the capability catalogue and uses explicit test-only doubles for Android's overlay permission/service state. A new smoke scenario validates runtime flags and tool restrictions.

## Current capability limits

The only executable Agent Broker tool remains `LIST_WORKSPACE_FILES`. It lists item names in the selected app-private workspace and verifies compact inventory evidence. It does not read or edit contents. UI-only/manual sections are not advertised as autonomous tools. Arbitrary shell execution through Agent Broker, file write/delete, autonomous web-page reading, external-app clicks, MediaProjection reading and APK building are not connected.

The system overlay needs the user's Android `SYSTEM_ALERT_WINDOW` permission and can still be covered by protected system surfaces or affected by manufacturer background policies. It is not AccessibilityService automation and does not read or control other apps.

## Verification evidence

- Python suite: **96/96 passed** (`python3 -m unittest discover -s tests -p 'test_*.py'`).
- Agent Core pure-JVM smoke: **14 scenarios passed**, including runtime capability-map assertions. The harness uses test doubles and is not Android Gradle validation.
- Spider Media URL policy JVM smoke: **15 assertions passed**.
- UI smoke: all **8 Chromium scenarios passed**, no uncaught page errors.
- HTML check: **131 unique IDs**, one inline script syntactically clean.
- Packaged Android HTML matched `app-web/ii-pauk.html` byte-for-byte.
- Local LLM Kotlin source type-check against signature-only Android stubs passed; SQLite task repository Kotlin type-check against signature stubs passed.
- `python3 -m py_compile backend/app/main.py scripts/*.py tests/*.py` and shell `bash -n` checks passed.

The aggregate `scripts/verify_project.sh` did **not** finish within the available command time limit, even though the above components were run and passed separately. The release-gate message `Linux runtime: NOT READY` is expected because a Linux runtime bundle has not been built.

## Explicitly not verified

There is no Android SDK/Gradle/NDK toolchain or emulator/device in this environment. No Android APK was assembled or installed. The `TYPE_APPLICATION_OVERLAY` source, permission prompt flow, touch gestures, device rotations/safe areas, foreground-service lifetime, terminal PTY, native llama.cpp inference and live Yandex provider connection still require a real Android build and device acceptance run. This package is a source checkpoint, not a tested release APK.

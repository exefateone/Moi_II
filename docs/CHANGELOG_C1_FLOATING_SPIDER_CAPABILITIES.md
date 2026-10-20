# C1 — floating Spider edge-peek and runtime capability map (17 Oct 2026)

- Kept the in-app Spider above navigation/tabs and modal content while making the action panel higher still and scrollable on smaller screens.
- Added idle edge tuck/peek inside the app; active dialogs, voice, text entry and work state delay retraction. A tap makes the spider crawl back out.
- Added/extended native `TYPE_APPLICATION_OVERLAY`: drag-to-edge, delayed partial retraction, touch-to-expand, quick panel, explicit “К краю”, notification stop action and service cleanup.
- Added `SpiderCapabilityCatalog`, refreshed on-device flags for overlay permission/request, local engine/model and stored backend configuration. Unverified device/provider status is shown explicitly.
- Injected the capability map into the local LLM conversation prompt, strengthened backend chat limits, and clarified the local planner's executable tool inventory.
- Added a visible capability-map dialog to the native Agent screen and “Возможности Паука” shortcut in the in-app quick panel.
- Updated the pure JVM Agent Core harness with test doubles for permission/overlay inspection so it continues to compile the updated runtime.
- Contract tests assert overlay stacking, tuck/reveal behaviour, current capability teaching, and packaged HTML byte equality.

The only executable Agent Broker tool remains `LIST_WORKSPACE_FILES`. No arbitrary shell action, file-content mutation, browser automation, external-app accessibility automation, MediaProjection capture or APK build pipeline was added. Android SDK/Gradle/NDK and a test device remain unavailable in this environment; system overlay, PTY and native LLM still require Android build/device acceptance.

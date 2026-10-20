# Changelog — ИИ Паук v0.37 Agent + Media Foundation

## Added to source
- Adopted a layered architecture inspired by public agent design principles: typed tasks/tools, separately evaluated policy, request-scoped approval identifiers and audit trail.
- Added `SpiderAgentModels.kt` with task/status enums and typed tool IDs. Generic shell execution is intentionally absent.
- Added `SpiderPolicyEngine.kt`: policy assigns risk itself, checks network access, requires explicit approval for writes/exec tests/downloads/package installation/deletion/external publishing, and denies malformed requests.
- Added `SpiderAuditLog.kt`: app-private append-only JSONL entries with bounded summaries and common secret-pattern redaction.
- Updated the Kotlin Gradle plugin to 2.2.0 to align with Media3 1.11.1 toolchain metadata.
- Added Media3 1.11.1 dependencies and native `SpiderMediaActivity` with an Android document picker and `PlayerView`.
- Added `SpiderPlaybackService` based on `MediaSessionService` for background audio/session controls.
- Added `open_media` to the allowlisted native route gateway and a Spider Media menu entry.
- Updated UI markers to v0.37 and kept HTML preview messaging honest: native media requires the Android build; agent tool orchestration is not yet wired.
- Added architecture, policy, agent, media and next-step documentation.

## Not verified / not yet complete
- Gradle sync/build and Media3 dependency resolution.
- Native app launch and media playback on a physical device/emulator.
- Notification/lock-screen/headset controls and background lifecycle.
- The policy engine is not yet connected to the executor or approval UI; audit log is not yet wired to actual tool calls.
- No real PTY, installed Linux userland, LLM provider, remote worker, complete autonomous task manager, or release APK.

# Deep audit — v0.37 Agent + Media Foundation

## Starting point (v0.36)
The delivered ZIP contained a polished HTML shell, native Android browser source, URL policy and a minimal FastAPI scaffold. It correctly did not claim a real PTY terminal, installed Linux userland, connected LLM, overlay, or built APK. Workspaces existed as private native directories but were not yet bound to a true PTY `cwd`.

## Architecture changes
- Added typed agent task/step/tool contracts and a policy engine that assigns risk in trusted code.
- Added an app-private append-only audit logger. Its records contain bounded summaries and common-pattern redaction; this is defense in depth, not a guarantee that any arbitrary input is secret-free.
- Added the Media3 native player Activity, system document picker, shared playback service and bridge/menu route.
- Kept the privileged bridge minimal and did not add generic terminal or filesystem methods.
- Preserved the Linux runtime `not_built` release gate.

## Risk/review notes
1. Agent contracts and policy are not connected to a task executor or visible approval UI yet. Do not describe them as a complete Sentinel security boundary.
2. The media code has not been compiled in this environment. Verify actual Media3 1.11.1 API compatibility in Gradle and on a device; this source change is not build evidence.
3. `SpiderPlaybackService` is non-exported, limiting other apps' direct MediaSessionController access by design. Verify system notification and lock-screen controls still behave as intended.
4. Media3 decoder support depends on Android/device capabilities; a list of common extensions cannot guarantee codec support.
5. Current bridge reply `accepted` means the route was accepted, not that the Activity rendered or media started.
6. Web and downloaded content remains untrusted input. Any future page reader must return bounded data and must not expose the browser's native bridge.

## Required gates
- Python contract/static verification.
- Gradle Sync and assembleDebug with logs.
- Instrumented bridge tests.
- Physical-device browser and media tests.
- Policy-engine unit tests, visible approval UI, request hash binding and audit integration.
- PTY and Linux remain separate milestones with their own verification evidence.

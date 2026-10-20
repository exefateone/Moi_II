# ИИ Паук v0.38 — Stage C1 provider planner checkpoint

**Date:** 15 October 2026  
**Product version:** v0.38 (incremental source checkpoint; not a release APK).

## Added

- Authenticated `POST /api/agent/plan` endpoint in `backend/app/main.py`. It uses an OpenAI-compatible chat-completions API configured through server environment variables; provider keys are never sent to Android.
- Request-body cap (16 KiB), goal and task ID validation, provider response cap (24 KiB), bounded model content, provider timeout, HTTPS-only provider base URL and redacted provider failure details.
- Exact tool allowlist: only `LIST_WORKSPACE_FILES` is permitted. Unsupported goals return a clear HTTP 422 code rather than inventing a tool or a fake execution result.
- Android `HttpSpiderTaskPlanner`: HTTPS endpoint validation, blocked redirects, connect/read timeouts, request/response bounds, task-ID binding and step validation. Model-supplied step status/result summary is ignored; coordinator resets all accepted steps to pending.
- `SpiderProviderConfigStore`: encrypts the app-to-backend bearer token using Android Keystore AES-GCM. The provider model API key belongs only on the server.
- `SpiderAgentActivity`: native UI for backend endpoint/token settings and a plan-only task request. Network requests use a worker thread. Added `open_agent` to the origin-scoped native bridge and a web quick-menu shortcut.
- Backend mock tests and Android JUnit/instrumentation test sources for endpoint and plan validation.

## Verification

| Check | Result |
|---|---|
| Python contract/runtime/backend tests | **53/53 passed** |
| HTML and JavaScript static checks | **PASS — 129 unique IDs** |
| Native HTML asset sync | **PASS** |
| Chromium UI smoke | **PASS — 8 scenarios; no uncaught page errors** |
| Existing pure Kotlin/JVM Agent Core smoke | **PASS — 5 grouped scenarios** |
| Planner + config-store Kotlin syntax/type check against API-signature stubs | **PASS** |
| Native task UI Kotlin syntax/type check against Android UI signature stubs | **PASS** |
| Android Gradle compile/JUnit | **NOT RUN — Gradle and Android SDK unavailable** |
| Live provider HTTP request | **NOT RUN — no server/provider credentials configured** |
| APK build / physical device | **NOT VERIFIED** |

Stub-based Kotlin checks are only syntax/type checks against minimal API signatures; they are not Android SDK compilation and do not validate runtime behavior.

## Remaining C1 work

1. Configure/deploy the HTTPS backend with `SPIDER_API_TOKEN`, `PROVIDER_API_KEY`, `PROVIDER_BASE_URL`, and `PROVIDER_MODEL`; then run a live plan request from the native task screen.
2. Add workspace selection and request-bound approval UI.
3. Connect approved steps to the broker, starting with the read-only workspace-list handler; verify actual results independently.
4. Persist request-ID replay protection and improve restart recovery.
5. Run official Gradle unit/instrumentation tests, build APK and perform device acceptance.

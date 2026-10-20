# ИИ Паук — архитектура v0.37 (Agent + Media Foundation)

## Принцип проекта

Паук остаётся отдельным управляемым Android-виджетом/быстрым входом, а не превращается в основную панель, которая постоянно занимает экран. Внутри приложения есть отдельные рабочие инструменты. Виджет только открывает нужный инструмент и не получает права на произвольное выполнение команд.

## Архитектура — целевая схема

```text
Android App (com.iipauk.spider)
├── Spider UI — доверенная локальная HTML-оболочка
│   ├── Chat / task request
│   ├── Task progress and approvals
│   └── entry points: Browser / Media / Terminal / Runtime / AI
│
├── Native Route Gateway (allowlist, exact app-shell origin)
│   ├── open_browser  ──> SpiderBrowserActivity (isolated WebView)
│   └── open_media    ──> SpiderMediaActivity (Media3)
│   └── No generic exec, filesystem or permission bridge
│
├── Spider Agent Platform
│   ├── Spider Agent Core (future orchestration)
│   │   ├── Task Planner
│   │   ├── Task State / Checkpoints
│   │   ├── Tool Router (typed tool IDs)
│   │   ├── Result Verifier
│   │   └── Recovery / Cancellation
│   ├── Spider Policy Engine / Sentinel (source foundation)
│   │   ├── risk assigned by trusted code, not model output
│   │   ├── outbound network gate
│   │   ├── request-scoped user approvals
│   │   └── deny-by-default for unknown capabilities
│   ├── Spider Audit Log (app-private JSONL, redacted summaries)
│   ├── Spider Memory (future project/user memory repository)
│   └── Spider Code (future coding workflow built on actual tools)
│
├── Spider Workspace
│   ├── WorkspaceRepository (private directories / partial UI binding)
│   ├── Spider Terminal (future real PTY; not implemented yet)
│   └── Spider Linux (separately validated app-specific userland; not built)
│
├── Spider Media
│   ├── SpiderMediaActivity — native Media3 PlayerView, multi-file picker, HTTPS URL input, playlist queue, HLS/DASH source (Android verification pending)
│   ├── SpiderPlaybackService — MediaSessionService source for audio background playback
│   ├── Media metadata / playlists / resume position (future)
│   └── transcription / media analysis / FFmpeg operations (future)
│
├── Spider Browser
│   ├── independent native WebView
│   ├── HTTP/HTTPS only; TLS errors fail closed
│   └── no privileged JavaScript bridge for visited sites
│
└── Optional backend (scaffold only)
    ├── authenticated model/provider adapter (future)
    ├── remote worker / Linux VM (future)
    └── quota, redacted telemetry and task recovery (future)
```

## Current v0.37 source status

| Capability | Status in this archive | What that means |
|---|---|---|
| Trusted HTML shell | Source + static tests | Not Android-device evidence |
| Native Spider Browser | Source improved, build/device QA pending | External pages do not get the privileged bridge |
| Native media player | Media3 Activity/Service source and menu route added | Not yet Gradle-compiled or run on a phone |
| Agent contracts | Typed task/tool models implemented in source | No task orchestration or tool execution loop yet |
| Policy engine | Source-level typed allowlist and per-request approval decisions | Not yet connected to an executor/UI approval workflow |
| Audit log | App-private append-only JSONL source, summary redaction | Not yet wired into real tool calls |
| Terminal PTY | Not connected | Current terminal screen must remain honest placeholder |
| Linux userland | Not built | Runtime manifest remains `not_built` |
| LLM/provider | Not connected as a production provider | Local planner/router is not a full LLM |
| Agent task manager / checkpoints | Planned | Must be implemented after tool contracts and true execution capabilities |
| Overlay widget | Planned | Optional, user-controlled Android component |
| APK | Not built/verified here | Build, installation and device evidence required |

## Security boundaries

1. The trusted HTML shell loads only from `https://appassets.androidplatform.net/assets/web/ii-pauk.html`.
2. The native message listener validates the exact trusted origin, main frame, and current app-shell URL. Native routes remain individually allowlisted.
3. The external browser Activity never receives the privileged bridge.
4. `open_media` opens the native player and may carry a separately validated direct HTTPS media URL; local file access is gated by the Android document picker. No broad storage access is required for this flow. HLS and DASH use matching Media3 modules; actual playback still requires Android build/device QA.
5. No `executeShell(command)` bridge is allowed. Future terminal actions must use separately implemented manual PTY controls or typed, policy-checked agent tools.
6. Model-generated text, web pages, downloaded files and media metadata are untrusted input, never permission or policy instructions.
7. All agent-initiated outbound requests must pass through the policy gateway. High-impact actions require fresh, request-specific user approval.
8. Runtime archives must pass provenance, digest, ABI, size, path-traversal, symlink and file-type checks before install; the runtime stays blocked while `not_built`.
9. Secrets and raw tool payloads must not be written into memory or audit logs. Provider credentials stay server-side or in a platform-protected secret store.

## Architecture influences

The agent workflow adapts general principles publicly described by Meta Muse: delegated goals, a dedicated working environment, a separate safety gate, persistent task context and auditability. It does not copy Meta's private implementation or require Meta's model. The security gateway is our own implementation and must be verified independently.

Native audio/video playback uses AndroidX Media3 1.11.1 (`ExoPlayer`, `PlayerView`, `MediaSessionService`). The release is the latest stable version listed by Android Developers on 12 October 2026; Media3 documentation recommends hosting the player and session in a service when playback needs to continue outside the foreground Activity.

References:
- Meta Muse overview: https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/
- AndroidX Media3 overview: https://developer.android.com/media/media3
- Media3 background playback: https://developer.android.com/media/media3/session/background-playback

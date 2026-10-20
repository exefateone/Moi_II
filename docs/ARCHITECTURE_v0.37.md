# Target architecture map — Spider v0.37 and onward

This is the implementation map for incremental work. Items marked **source** exist in Kotlin/HTML but are not necessarily built. Items marked **future** are design targets only.

```text
ИИ ПАУК
│
├─ 1. UI SHELL [source]
│  ├─ Chat / menus / profile / local preferences
│  ├─ Spider floating widget [future native overlay]
│  └─ Status and approval screens [approval UX future]
│
├─ 2. ROUTE GATEWAY [source]
│  ├─ exact origin + main-frame checks
│  ├─ open_browser -> isolated native WebView
│  └─ open_media -> native Media3 screen
│
├─ 3. SPIDER AGENT PLATFORM
│  ├─ Agent Models [source]
│  ├─ Policy Engine / Sentinel decision [source, not executor]
│  ├─ Audit Log [source, not wired to tool calls]
│  ├─ Task Planner / State Repository [future]
│  ├─ Typed Tool Router / Tool Executor [future]
│  ├─ Approval UI / one-use grant store [future]
│  ├─ Result Verifier / Recovery / Cancellation [future]
│  ├─ Project + user memory repository [future]
│  └─ Spider Code workflow [future]
│
├─ 4. WORKSPACE LAYER
│  ├─ Private project directories [partial source]
│  ├─ Real interactive PTY terminal [future]
│  └─ Verified Linux userland bundle [future; currently not_built]
│
├─ 5. BROWSER
│  ├─ Native HTTP/HTTPS WebView [source]
│  ├─ Safe URL / TLS fail-closed / downloads [source]
│  └─ Separate, permission-limited content reader for AI [future]
│
├─ 6. MEDIA
│  ├─ Media3 PlayerView + document picker [source]
│  ├─ MediaSessionService [source]
│  ├─ Library / playlist / history / resume [future]
│  └─ FFmpeg jobs / ASR / media understanding [future]
│
├─ 7. OPTIONAL BACKEND
│  ├─ authenticated model adapter [future; FastAPI scaffold only]
│  ├─ remote isolated worker/VM [future]
│  └─ quotas, recovery, redacted logs [future]
│
└─ 8. RELEASE / TRUST
   ├─ static checks [source]
   ├─ Kotlin/Gradle build [not yet confirmed]
   ├─ instrumented/device tests [pending]
   └─ signed APK + runtime provenance [future]
```

## Order of implementation

1. Compile and run the new Media3 source; fix all real Gradle/Kotlin/device errors.
2. Finish native browser and media device QA.
3. Add true PTY with lifecycle/cancellation tests.
4. Connect real workspaces to PTY working directories with canonical path checks.
5. Build and validate a userland bundle separately; never claim Linux is installed before it passes the release gate.
6. Implement persistent task repository and task manager.
7. Connect the policy engine to per-tool confirmation UI, one-use approvals and audit log.
8. Implement only typed tool handlers that exist and are independently tested.
9. Add verification, retry limits, checkpoints and recovery.
10. Connect an LLM/provider after local capability, privacy, authentication and budget requirements are clear.
11. Add project memory and Spider Code iteration; then optional remote isolated worker.

## Design constraints retained

- No generic shell bridge and no silent root requirement.
- Browser pages are untrusted data and never have native bridge access.
- Model credentials never live in HTML assets or WebView local storage.
- Paук widget remains separate and opt-in.
- All advertised capability flags must reflect real verified capability rather than a mock UI.

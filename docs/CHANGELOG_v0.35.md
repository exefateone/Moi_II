# Changelog — v0.35 Foundation

- Created one canonical repository layout for web UI, Android project, backend scaffold, Linux runtime validation, docs, tests and snapshots.
- Fixed visible release-label mismatch in the current HTML snapshot (v0.34 package had residual v0.33 labels); current app-web is marked v0.35.
- Added a Gradle Android Studio project skeleton.
- Added trusted app-shell WebViewAssetLoader and origin-scoped WebMessageListener bridge; bridge allowlist contains only `open_browser`.
- Added separate native Spider Browser with HTTP/HTTPS URL policy, TLS fail-closed behavior, Safe Browsing setting, no native bridge for web sites, and DownloadManager hook.
- Added native app-private WorkspaceRepository (not yet wired into the HTML UI or PTY cwd).
- Added explicit SpiderTerminalActivity placeholder; PTY is not claimed as implemented in this scaffold.
- Added backend health scaffold; AI provider and agent execution remain disabled.
- Added package tests, Kotlin URL policy tests, starter Android instrumentation test, status matrix and roadmap.

## Not completed in this version

- No Android Gradle build or APK produced in this environment.
- No device validation of WebView or download behavior.
- No actual PTY integration.
- No full Linux userland, local LLM model, overlay service or deployed backend.

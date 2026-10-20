# Changelog — ИИ Паук v0.36

- Fixed the stale UI/native bridge mismatch (`SpiderNative` vs native `spiderNative`).
- Removed early JavaScript facade methods for terminal execution that did not exist in Android source.
- Exposed only browser navigation and truthful native capability metadata from the trusted shell.
- Routed the Android Browser UI to the independent native SpiderBrowserActivity; plain web mode retains its sandboxed iframe prototype.
- Added upload file chooser handling, Safe Browsing fail-close, renderer-crash cleanup, lifecycle handling and cancellation of pending file selection.
- Added strict URL validation for control chars, backslashes, credentials and malformed/out-of-range ports.
- Removed WebView session-cookie forwarding to DownloadManager to avoid authenticated-cookie leakage over uninspected redirect chains; public downloads remain supported and filenames are sanitized. Legacy Android (API 28 and lower) now requests the storage permission before writing to public Downloads.
- Updated all current release markers to v0.36, corrected inherited claims about PTY being ready and synced Android HTML assets.
- Added source tests and Gradle compatibility notes; Android compile remains unverified.

Unverified: Gradle compilation/APK packaging, physical-device Browser QA, actual file upload/download and renderer/TLS tests. PTY, Linux userland, local LLM and overlay remain unavailable.


## Browser correctness/security follow-up
- Removed a native capture-phase click handler that could bypass the app's own browser navigation state and open the default site instead of the selected tab. The HTML UI now invokes the actual native bridge itself with the selected tab URL.
- Added address-bar search semantics: domain-like input navigates directly, ordinary text becomes a DuckDuckGo query, and explicit non-web schemes are rejected rather than searched.
- Added main-frame network/HTTP/TLS/Safe Browsing error status retention so an error is not overwritten by progress or page-finished callbacks.
- Removed session-cookie forwarding to DownloadManager to avoid leaking authenticated cookies over uninspected redirects. Public downloads remain supported; authenticated downloads need a redirect-aware downloader.
- Added exact app-shell URL verification to the trusted bridge, aligned HTML and native URL-policy checks, fixed literal-only interpolation in network/HTTP error messages, retained TLS/Safe Browsing failure status, expanded smoke tests and static contract tests.

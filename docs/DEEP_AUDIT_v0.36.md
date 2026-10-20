# Deep audit — v0.35 → v0.36

## Key defect fixed
Android registers a lowercase `spiderNative` object, while the older HTML bootstrapping script looked for uppercase `SpiderNative`, then exposed placeholder methods such as `openSpiderTerminal` and `executeAllowedCommand`. This was inconsistent with the actual native host and made UI readiness checks misleading. v0.36 removes that early facade; the trusted host injects only the actual browser navigation API and truthful capability metadata after loading the app shell.

## Browser changes in source
- `SpiderBrowserActivity` stays isolated from the native bridge.
- Main-frame navigation allows HTTP/HTTPS only; SSL errors cancel; Safe Browsing returns to safety.
- File input requests are routed through Android's file chooser and pending callbacks are cancelled on failure/activity teardown.
- Renderer crash removes/destroys the dead WebView rather than trying to reuse it.
- Added activity pause/resume calls.
- DownloadManager does not receive WebView session cookies, avoiding credential leakage across redirects. Filenames are bounded and sanitized; authenticated-only downloads need a dedicated redirect-aware downloader. Android 8.1 and older now request storage permission before writing into public Downloads, but permission flow remains source-only until device QA.
- URL policy rejects control characters, backslashes, embedded credentials, invalid schemes and malformed/out-of-range ports.

## UI honesty
Fixed inherited stale version strings and a stale claim that a real Android `/system/bin/sh` PTY was already integrated. v0.36 correctly leaves Terminal and Linux marked pending.

## Remaining issues
1. `openSpiderBrowser()` only means the native message was posted; it does not confirm successful Internet navigation. The incorrect capture-phase click handler was removed because it bypassed app-local tab state. The trusted bridge additionally checks that the main frame is still the exact app-shell URL before dispatching a message.
2. Browser is a single WebView activity: no persistent multi-tab UI/bookmarks and no download manager screen. Plain-text search input is converted to a DuckDuckGo query. Page text reading is not wired in the native browser yet.
3. HTML workspace metadata is still separate from the native WorkspaceRepository; no PTY `cwd` binding exists.
4. PTY and Linux userland are not integrated.
5. No Android SDK/Gradle and DNS was unavailable here; Kotlin compile/device QA were not run.
6. `usesCleartextTraffic=true` remains required for direct HTTP websites; mixed content in HTTPS pages is blocked. This is an explicit trade-off for a general web browser; consider a future user-configurable HTTPS-only mode.
7. File upload passes a user-selected URI to the requesting web page. Keep the privileged app bridge out of the browser activity.

## Release evidence
Automated static checks and tests may pass in this package; they cannot prove `assembleDebug`, actual WebView navigation, file upload/download, Safe Browsing, TLS behavior or renderer lifecycle on a physical device.

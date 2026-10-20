# Security rules

## Native bridge
- Only the trusted local shell may register the bridge.
- Exact origin `https://appassets.androidplatform.net`; require main frame, HTTPS, host equality and default port.
- Fail closed when `WEB_MESSAGE_LISTENER` is unavailable. Never fall back to `addJavascriptInterface`.
- Validate request JSON length and method allowlist. No generic `exec`, arbitrary file access, permission toggles or unrestricted Intents.
- External internet WebView must have no bridge and no file/content access.

## Browser
- Only `http` and `https` are navigable. Android cleartext networking is enabled because this is a general-purpose browser; the trusted app shell itself remains HTTPS, mixed content remains blocked, and the browser receives no native bridge.
- URLs with embedded username/password are rejected.
- `file:`, `content:`, `javascript:`, `data:`, `intent:` and unknown schemes are blocked.
- TLS/certificate errors cancel; never show a "continue anyway" path.
- Mixed content disabled, Safe Browsing enabled where supported, downloads constrained to HTTP(S).
- HTML content read by AI is untrusted input, never instruction authority.

## Terminal / Linux runtime
- Never request root as a shortcut.
- Manual terminal and AI tools use separate execution paths.
- Linux archive extraction must reject path traversal, symlink/hardlink escape, special files, duplicate paths and oversized archives.
- Verify SHA-256 against a trusted signed/provenance record; hash alone is not proof of who built it.
- Install/update must be atomic and rollbackable; no archive should be installed while status is `not_built`.
- Avoid automatically running scripts after extraction.

## Backend
- Keep credentials on server only; `.env` is untracked.
- Backend now has per-route in-process rolling-window quotas (default 20 requests/minute per configured token), a four-call provider concurrency ceiling, `Retry-After`, bounded request/response sizes, timeouts, strict model/tool validation and redacted provider errors. These controls are process-local and not distributed. Before internet exposure: TLS, reverse-proxy/API-gateway rate limits and spend quotas, authentication/session identity, deployment-wide monitoring/alerting, request limits, strict model allowlist, and redacted logs.
- CORS is not authentication.

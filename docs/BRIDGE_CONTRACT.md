# Native bridge contract — v0.37

## Allowed methods

### `open_browser`
Opens the independent native Spider Browser Activity with a validated initial HTTP/HTTPS URL.

Envelope:
```json
{"id":"request-uuid","method":"open_browser","url":"https://example.com/"}
```

### `open_media`
Opens the native Spider Media screen. The Activity uses Android's document picker for local/provider files and accepts an optional direct HTTPS media URL. It never accepts a filesystem path, command or media bytes over the bridge.

Envelope without initial source:
```json
{"id":"request-uuid","method":"open_media"}
```

Envelope with an initial HTTPS source:
```json
{"id":"request-uuid","method":"open_media","url":"https://media.example.test/video.mp4"}
```

The URL is independently parsed and must be HTTPS, must have a host, and must not contain credentials. The native acknowledgement only means the route passed bridge checks and the Activity launch was requested; it does not prove the remote response or playback succeeded.

## Explicitly not exposed

- `open_terminal`
- arbitrary shell / `exec`
- filesystem read/write from JavaScript
- reading arbitrary visited-page DOM from native APIs
- enabling Android permissions silently
- screen capture/accessibility actions
- passing arbitrary native intents, paths or provider credentials

## Validation

MainActivity requires the exact trusted HTTPS asset origin, main-frame source and exact current trusted app-shell URL. The JSON message size is bounded. Each native method is individually allowlisted; unknown methods fail closed. Browser URLs receive independent `SafeUrlPolicy` validation. Any optional `open_media.url` is validated independently by `SpiderMediaUrlPolicy`; network media URLs are HTTPS-only. Local files still go through the Android document picker.

Future methods need a JSON schema, capability declaration, permission/risk classification, timeout/cancellation behavior, result status, audit policy and tests. Never add a generic `executeShell(command)` bridge.

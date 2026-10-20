# Stage C — Spider Media player expansion (v0.38)

## Added
- Expanded native `SpiderMediaActivity` into a source player UI with Media3 `PlayerView`, HTTPS source entry, local multi-file picker, queue preview, previous/next and clear queue.
- Added Media3 HLS and MPEG-DASH modules (`media3-exoplayer-hls`, `media3-exoplayer-dash`) at version `1.11.1`.
- Added `SpiderMediaUrlPolicy`: HTTPS-only URL parsing; reject embedded credentials, unsupported schemes, malformed ports, control characters, backslashes and oversized input.
- Detect `.m3u8` and `.mpd` by URL path rather than query string; add MIME hints for HLS and DASH.
- Keep signed URL query strings out of the displayed media title, metadata, and normal status text.
- Keep playback ownership in `SpiderPlaybackService`/`MediaSessionService` for background audio and system media controls.
- Extend the allowlisted `open_media` bridge route with an optional validated HTTPS media URL.
- Add unit tests for URL policy, adaptive stream MIME detection and token-safe display titles; update HTML modal and media contract.

## Not yet confirmed
- Android Gradle compilation, APK creation and device playback.
- Live HLS/DASH stream playback and OEM-specific background media controls.
- Access to SMB/NFS shares or arbitrary RTSP; for now use direct HTTPS media URLs or Android document providers.

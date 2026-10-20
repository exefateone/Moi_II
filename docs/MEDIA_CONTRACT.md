# Spider Media contract — v0.38, Stage C

## Source implementation

- `SpiderMediaActivity`: native Android Media3 screen with `PlayerView`, multi-document Android Storage Access Framework picker, HTTPS media URL entry, playlist queue, previous/next controls, clear queue and playback status.
- `SpiderPlaybackService`: owns the shared `ExoPlayer` and `MediaSessionService`, enabling background audio and Android/system media controls at source level.
- Media3 dependencies are pinned consistently to `1.11.1`: `media3-exoplayer`, `media3-exoplayer-hls`, `media3-exoplayer-dash`, `media3-ui`, and `media3-session`.
- `open_media` remains allowlisted. It can open the media screen alone or pass an optional validated HTTPS URL through `EXTRA_MEDIA_URL`; it does not accept filesystem paths or shell commands.

## Source types

1. **On-device files / Android document providers:** the user explicitly selects one or more `audio/*` and `video/*` documents. The app tries to preserve read permission for selected URIs and builds a queue from the selected files. Cloud/remote storage works only if an Android document provider exposes the selected item as a readable URI.
2. **Direct internet/server HTTPS:** user enters a direct HTTPS media URL. The parser rejects plain HTTP, non-HTTPS schemes, credentials embedded in the URL, control characters, backslashes, malformed ports and oversized input. Signed query parameters are allowed so expiring/CDN links can work, but raw URLs are not copied into status text or media metadata.
3. **HLS:** `.m3u8` URLs are explicitly tagged as `application/x-mpegURL` and use the Media3 HLS module. Supported capabilities include VOD/live HLS subject to the stream and device's codecs.
4. **MPEG-DASH:** `.mpd` URLs are explicitly tagged as `application/dash+xml` and use the Media3 DASH module.
5. **Progressive files/streams:** other HTTPS endpoints are handed to Media3 for normal format sniffing. The endpoint must return actual playable media, not an HTML webpage or login page.

## Controls / behavior

- `PlayerView` provides built-in playback controls, seeking and buffering indicator.
- Multiple local files and remote URLs can be queued. The app shows a bounded queue preview and supports moving to previous/next item.
- `MediaSessionService` owns the player so audio can continue when the Activity is backgrounded; notification, lock-screen, headset and OEM-specific behavior still needs real device verification.
- A URL is only accepted by the source policy; this alone does not confirm that the remote server responds or that its codec is supported.

## Security and limits

- Network URLs must use HTTPS and must not embed `user:password@host`. The default manifest disallows cleartext traffic.
- Signed URLs may include sensitive query tokens; visible title/status intentionally omit the query. Do not add verbose logging of the full URI.
- The player does not bypass TLS errors, download or store remote files, transcode, decrypt DRM, scrape login pages, or include provider credentials.
- User-selected local/document URIs are used rather than broad access to all files.
- SMB/NFS browsing and arbitrary RTSP are not included in this milestone; a NAS must expose a direct HTTPS stream or a document provider must surface its files.
- Codec/container compatibility depends on installed Android decoders and Media3 modules; playback of every format is not guaranteed.
- HTML-only preview cannot exercise native Media3 and states that native playback requires the Android application.

## QA status

**Not yet Android verified.** The repository includes source-level / static contract checks and URL-policy unit tests, but Android SDK/Gradle are not installed in the current execution environment, so no claim is made that Kotlin Android sources compile, remote playback succeeds, background playback works on a device, or an APK has been built.

After Android build becomes available, QA checklist:

1. Run Gradle `testDebugUnitTest` and `assembleDebug`.
2. Open one local MP3/AAC/WAV and one H.264 MP4 from internal storage/Downloads.
3. Queue multiple local files and verify next/previous, seek and queue clearing.
4. Test a direct HTTPS MP3/MP4 with byte-range support where applicable.
5. Test a public/sample HTTPS HLS `.m3u8` VOD stream and, separately, live HLS.
6. Test a valid HTTPS DASH `.mpd` stream.
7. Confirm HTTP, malformed URLs and URLs with embedded credentials are rejected.
8. Test unavailable/expired URLs, TLS errors, redirects and unsupported codec error handling.
9. Test background audio, notification, lock screen, headset controls, Activity close/reopen, rotation and process lifecycle.
10. Confirm no signed URL tokens appear in logs, visible queue titles or status messages.

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/MediaUrlPolicySmoke.kt" <<'KOTLIN'
package com.iipauk.spider

fun main() {
    var checks = 0
    fun expect(condition: Boolean, label: String) {
        check(condition) { "FAIL: $label" }
        checks++
    }
    val direct = SpiderMediaUrlPolicy.parseHttpsUrl("https://cdn.example.test/film.mp4?signature=private")
    expect(direct != null && direct.scheme == "https", "HTTPS progressive URL accepted")
    expect(direct != null && SpiderMediaUrlPolicy.displayTitle(direct).contains("film.mp4"), "clean media title")
    expect(direct != null && !SpiderMediaUrlPolicy.displayTitle(direct).contains("private"), "signed token not displayed")
    expect(SpiderMediaUrlPolicy.parseHttpsUrl("media.example.test/film.mp4")?.toASCIIString() == "https://media.example.test/film.mp4", "bare host gets HTTPS")
    expect(SpiderMediaUrlPolicy.parseHttpsUrl("http://media.example.test/film.mp4") == null, "HTTP rejected")
    expect(SpiderMediaUrlPolicy.parseHttpsUrl("javascript:alert(1)") == null, "script scheme rejected")
    expect(SpiderMediaUrlPolicy.parseHttpsUrl("file:///sdcard/film.mp4") == null, "file scheme rejected")
    expect(SpiderMediaUrlPolicy.parseHttpsUrl("https://user:pass@media.example.test/a.mp4") == null, "credentials rejected")
    expect(SpiderMediaUrlPolicy.parseHttpsUrl("https://media.example.test:99999/a.mp4") == null, "invalid port rejected")
    expect(SpiderMediaUrlPolicy.parseHttpsUrl("https://media.example.test\\@evil.example/a.mp4") == null, "backslash rejected")
    expect(SpiderMediaUrlPolicy.parseHttpsUrl("https://media.example.test/\nfilm.mp4") == null, "control chars rejected")
    expect(SpiderMediaUrlPolicy.parseHttpsUrl("https://media.example.test/" + "x".repeat(9000)) == null, "oversized URL rejected")
    val hls = SpiderMediaUrlPolicy.parseHttpsUrl("https://cdn.example.test/live.m3u8?token=private")!!
    val dash = SpiderMediaUrlPolicy.parseHttpsUrl("https://cdn.example.test/manifest.MPD?token=private")!!
    expect(SpiderMediaUrlPolicy.inferMimeType(hls) == "application/x-mpegURL", "HLS detected from path")
    expect(SpiderMediaUrlPolicy.inferMimeType(dash) == "application/dash+xml", "DASH detected from path")
    expect(SpiderMediaUrlPolicy.inferMimeType(SpiderMediaUrlPolicy.parseHttpsUrl("https://cdn.example.test/watch?id=4")!!) == null, "query does not drive MIME inference")
    println("SpiderMediaUrlPolicy JVM smoke PASS: $checks assertions")
}
KOTLIN
kotlinc "$ROOT/android-project/app/src/main/java/com/iipauk/spider/SpiderMediaUrlPolicy.kt" "$TMP/MediaUrlPolicySmoke.kt" -include-runtime -d "$TMP/media-url-policy-smoke.jar"
java -jar "$TMP/media-url-policy-smoke.jar"

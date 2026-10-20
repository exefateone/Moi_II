package com.iipauk.spider

import java.net.URI

/**
 * HTTPS-only network source policy for Spider Media.
 * Keeps signed URL query parameters out of visible titles and refuses credentials/ambiguous input.
 */
internal object SpiderMediaUrlPolicy {
    private const val MAX_URL_LENGTH = 8192

    fun parseHttpsUrl(raw: String?): URI? {
        if (raw.isNullOrBlank() || raw.length > MAX_URL_LENGTH) return null
        val value = raw.trim()
        if (value.any { it.code < 0x20 || it.code == 0x7f } || '\\' in value) return null
        val candidate = if (value.contains("://")) value else "https://$value"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.isAbsolute || uri.isOpaque) return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        if (uri.rawAuthority.isNullOrBlank() || uri.rawAuthority.contains('@')) return null
        return uri.normalize()
    }

    /** Return MIME hints only for known adaptive manifests; progressive streams can be sniffed by Media3. */
    fun inferMimeType(uri: URI): String? = when {
        uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
        uri.path.orEmpty().endsWith(".mpd", ignoreCase = true) -> "application/dash+xml"
        else -> null
    }

    /** Never includes host query parameters, which may contain signed access tokens. */
    fun displayTitle(uri: URI): String {
        val last = uri.path.orEmpty().trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
        return (last ?: uri.host.orEmpty().ifBlank { "Сетевой поток" }).take(120)
    }
}

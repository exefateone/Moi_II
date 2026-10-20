package com.iipauk.spider

import android.net.Uri

/** Shared URL policy for native browser entry points. Never accepts app/file/intent schemes. */
object SafeUrlPolicy {
    private val allowedSchemes = setOf("http", "https")

    fun parse(raw: String?): Uri? {
        if (raw.isNullOrBlank() || raw.length > MAX_URL_LENGTH) return null
        // Reject control characters and backslashes before Uri parsing to avoid parser ambiguity.
        if (raw.any { it.code < 0x20 || it.code == 0x7f } || '\\' in raw) return null
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (!uri.isHierarchical) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in allowedSchemes) return null
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' || it == '\\' }) return null
        if (uri.userInfo != null || !uri.encodedUserInfo.isNullOrEmpty()) return null
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null

        // Reject explicitly malformed ports. `Uri.port` may return -1 for both no port and bad text.
        val authority = uri.encodedAuthority.orEmpty()
        if (authority.isEmpty()) return null
        val hostAndPort = authority.substringAfterLast('@')
        if (hostAndPort.startsWith("[")) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) return null
            val tail = hostAndPort.substring(close + 1)
            if (tail.isNotEmpty() && (!tail.startsWith(":") || tail.drop(1).isEmpty() || tail.drop(1).any { !it.isDigit() })) return null
        } else {
            val colon = hostAndPort.lastIndexOf(':')
            if (colon >= 0) {
                val portText = hostAndPort.substring(colon + 1)
                if (portText.isEmpty() || portText.any { !it.isDigit() }) return null
            }
        }
        return uri
    }

    fun normaliseAddress(raw: String): Uri? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_URL_LENGTH || text.any { it.code < 0x20 || it.code == 0x7f }) return null
        val candidate = if (text.contains("://")) text else "https://$text"
        return parse(candidate)
    }

    /** Domain-like input opens directly; ordinary words become a search. Explicit schemes never fall back to search. */
    fun addressOrSearch(raw: String): Uri? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_URL_LENGTH || text.any { it.code < 0x20 || it.code == 0x7f }) return null

        val explicitWebUrl = text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        if (text.contains("://")) return if (explicitWebUrl) normaliseAddress(text) else null

        val colon = text.indexOf(':')
        if (colon > 0) {
            val prefix = text.substring(0, colon)
            val hostPortException = prefix.contains('.') || prefix.equals("localhost", ignoreCase = true) || text.startsWith("[")
            val looksLikeScheme = prefix.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*$")) && !hostPortException
            if (looksLikeScheme) return null
        }

        val direct = normaliseAddress(text)
        if (direct != null) {
            val firstPart = text.substringBefore('/').substringBefore('?').substringBefore('#')
            val hostCandidate = if (firstPart.startsWith("[")) firstPart.substringBefore(']') + "]"
                else if (firstPart.count { it == ':' } == 1 && firstPart.substringAfterLast(':').all(Char::isDigit)) firstPart.substringBeforeLast(':')
                else firstPart
            val looksLikeHost = hostCandidate.equals("localhost", ignoreCase = true) ||
                hostCandidate.contains('.') || firstPart.startsWith("[")
            if (looksLikeHost) return direct
        }

        val search = Uri.parse("https://duckduckgo.com/").buildUpon()
            .appendQueryParameter("q", text)
            .build()
        return parse(search.toString())
    }

    private const val MAX_URL_LENGTH = 8192
}

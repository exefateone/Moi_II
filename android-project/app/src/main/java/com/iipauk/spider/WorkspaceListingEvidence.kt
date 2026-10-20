package com.iipauk.spider

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Compact, versioned evidence for a bounded workspace inventory. It contains no file contents or
 * raw paths. The verifier recomputes the digest from a fresh filesystem scan before accepting it.
 */
data class WorkspaceListingEvidence(
    val workspaceId: String,
    val entryCount: Int,
    val truncated: Boolean,
    val inventorySha256: String
) {
    fun encode(): String = "v1|$workspaceId|$entryCount|${if (truncated) 1 else 0}|$inventorySha256"

    companion object {
        private val WORKSPACE_ID = Regex("^[A-Za-z0-9_-]{1,120}$")
        private val SHA256_HEX = Regex("^[a-f0-9]{64}$")
        private const val MAX_EVIDENCE_LENGTH = 256
        private const val MAX_ENTRIES = 2_000

        fun parse(raw: String?): WorkspaceListingEvidence? {
            if (raw.isNullOrBlank() || raw.length > MAX_EVIDENCE_LENGTH) return null
            val parts = raw.split('|')
            if (parts.size != 5 || parts[0] != "v1") return null
            val workspaceId = parts[1]
            if (!WORKSPACE_ID.matches(workspaceId)) return null
            val count = parts[2].toIntOrNull()?.takeIf { it in 0..MAX_ENTRIES } ?: return null
            val truncated = when (parts[3]) {
                "0" -> false
                "1" -> true
                else -> return null
            }
            val digest = parts[4]
            if (!SHA256_HEX.matches(digest)) return null
            return WorkspaceListingEvidence(workspaceId, count, truncated, digest)
        }

        /** Length-prefix entries so the digest is unambiguous even if names contain separators. */
        fun digestPaths(paths: Collection<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            paths.sorted().forEach { path ->
                val bytes = path.toByteArray(StandardCharsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        fun fromPaths(workspaceId: String, paths: Collection<String>, truncated: Boolean): WorkspaceListingEvidence? {
            if (!WORKSPACE_ID.matches(workspaceId) || paths.size > MAX_ENTRIES) return null
            return WorkspaceListingEvidence(
                workspaceId = workspaceId,
                entryCount = paths.size,
                truncated = truncated,
                inventorySha256 = digestPaths(paths)
            )
        }
    }
}

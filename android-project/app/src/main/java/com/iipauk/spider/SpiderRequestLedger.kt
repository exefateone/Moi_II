package com.iipauk.spider

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private fun isValidRequestId(requestId: String): Boolean =
    requestId.length in 1..120 && requestId.none { it.code < 0x20 || it.code == 0x7f }

/** Result of trying to permanently consume a one-time tool request ID. */
enum class SpiderRequestClaimResult { CLAIMED, ALREADY_CLAIMED, STORAGE_UNAVAILABLE }

/** Claims must be durable before a tool's first side effect. Implementations fail closed. */
fun interface SpiderRequestLedger {
    fun claim(requestId: String): SpiderRequestClaimResult
}

/** Process-local ledger for isolated tests. Android runtime should use FileSpiderRequestLedger. */
class InMemorySpiderRequestLedger : SpiderRequestLedger {
    private val requestIds = mutableSetOf<String>()

    @Synchronized
    override fun claim(requestId: String): SpiderRequestClaimResult {
        if (!isValidRequestId(requestId)) return SpiderRequestClaimResult.STORAGE_UNAVAILABLE
        return if (requestIds.add(requestId)) SpiderRequestClaimResult.CLAIMED
        else SpiderRequestClaimResult.ALREADY_CLAIMED
    }
}

/**
 * App-private, restart-persistent replay ledger. Only SHA-256 digests are stored, not raw request IDs.
 * A corrupt or unsupported ledger is left untouched and all new claims fail closed; it is never
 * silently reset because doing so could re-enable previously consumed requests.
 */
class FileSpiderRequestLedger(private val ledgerFile: File) : SpiderRequestLedger {
    private val canonicalPath = ledgerFile.canonicalFile.absolutePath
    private val lock = locks.computeIfAbsent(canonicalPath) { Any() }

    override fun claim(requestId: String): SpiderRequestClaimResult = synchronized(lock) {
        if (!isValidRequestId(requestId)) return@synchronized SpiderRequestClaimResult.STORAGE_UNAVAILABLE
        val hashes = try {
            readHashesLocked()
        } catch (_: Exception) {
            return@synchronized SpiderRequestClaimResult.STORAGE_UNAVAILABLE
        }
        val digest = sha256Hex(requestId)
        if (digest in hashes) return@synchronized SpiderRequestClaimResult.ALREADY_CLAIMED
        if (hashes.size >= MAX_RECORDS) return@synchronized SpiderRequestClaimResult.STORAGE_UNAVAILABLE
        hashes += digest
        try {
            writeHashesLocked(hashes)
            SpiderRequestClaimResult.CLAIMED
        } catch (_: Exception) {
            SpiderRequestClaimResult.STORAGE_UNAVAILABLE
        }
    }

    private fun readHashesLocked(): LinkedHashSet<String> {
        if (!ledgerFile.exists()) return linkedSetOf()
        if (ledgerFile.length() !in MIN_STORE_BYTES..MAX_STORE_BYTES) {
            throw IOException("Request ledger size is invalid")
        }
        DataInputStream(BufferedInputStream(FileInputStream(ledgerFile))).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Request ledger signature is invalid")
            if (input.readInt() != SCHEMA_VERSION) throw IOException("Request ledger schema is unsupported")
            val count = input.readInt()
            if (count !in 0..MAX_RECORDS) throw IOException("Request ledger count is invalid")
            val hashes = linkedSetOf<String>()
            repeat(count) {
                val hash = input.readUTF()
                if (!HASH_PATTERN.matches(hash) || !hashes.add(hash)) throw IOException("Request ledger entry is invalid")
            }
            if (input.read() != -1) throw IOException("Request ledger has trailing data")
            return hashes
        }
    }

    private fun writeHashesLocked(hashes: Set<String>) {
        val parent = ledgerFile.absoluteFile.parentFile ?: throw IOException("Request ledger must be a file")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Request ledger directory is unavailable")
        if (!parent.isDirectory) throw IOException("Request ledger parent is not a directory")
        val temp = File(parent, "${ledgerFile.name}.tmp")
        try {
            val fileOutput = FileOutputStream(temp, false)
            DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(SCHEMA_VERSION)
                output.writeInt(hashes.size)
                hashes.sorted().forEach(output::writeUTF)
                output.flush()
                fileOutput.fd.sync()
            }
            try {
                Files.move(temp.toPath(), ledgerFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), ledgerFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (ledgerFile.length() > MAX_STORE_BYTES) throw IOException("Request ledger exceeded size limit")
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        }
    }

    companion object {
        private const val MAGIC = 0x5350524c // SPRL
        private const val SCHEMA_VERSION = 1
        private const val MAX_RECORDS = 10_000
        private const val MIN_STORE_BYTES = 12L
        private const val MAX_STORE_BYTES = 700_000L
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
        private val locks = ConcurrentHashMap<String, Any>()

        private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

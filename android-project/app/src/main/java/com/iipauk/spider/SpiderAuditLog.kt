package com.iipauk.spider

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID


/** A deliberately small, allowlisted audit reference for UI history (no raw summary or paths). */
data class SpiderAuditReference(
    val referenceId: String,
    val timestampEpochMs: Long,
    val toolId: String,
    val disposition: String,
    val resultCode: String
)

/** App-private, append-only audit trail. Stores summaries, never raw tool payloads or file contents. */
class SpiderAuditLog(context: Context) : SpiderAuditSink {
    private val logFile = File(File(context.filesDir, "audit"), "spider-audit.jsonl")

    /**
     * Returns references for one task from a bounded tail of the audit file. This intentionally
     * excludes arbitrary summaries, request IDs, user goals, URLs and filesystem paths.
     */
    fun safeReferencesForTask(taskId: String, limit: Int = 8): List<SpiderAuditReference> {
        if (taskId.isBlank() || taskId.length > 120 || limit !in 1..20) return emptyList()
        if (!logFile.isFile || !logFile.canRead()) return emptyList()
        return runCatching {
            val maxScanBytes = MAX_SCAN_BYTES
            val rawLines = RandomAccessFile(logFile, "r").use { file ->
                val start = (file.length() - maxScanBytes).coerceAtLeast(0L)
                file.seek(start)
                if (start > 0L) file.readLine() // discard a possibly partial first record
                val lines = ArrayList<String>()
                while (file.filePointer < file.length() && lines.size < 4096) {
                    val line = file.readLine() ?: break
                    if (line.isNotBlank()) lines += line
                }
                lines
            }
            rawLines.asSequence().mapNotNull lineLoop@{ line ->
                runCatching parseRecord@{
                    // The values used below are ASCII identifiers/enums. Summary and requestId are
                    // never read into the returned model, even if their contents are untrusted.
                    val json = JSONObject(String(line.toByteArray(Charsets.ISO_8859_1), StandardCharsets.UTF_8))
                    if (json.optString("taskId") != taskId) return@parseRecord null
                    val eventId = json.optString("eventId")
                    val tool = json.optString("toolId")
                    val disposition = json.optString("disposition")
                    val code = json.optString("resultCode")
                    if (!eventId.matches(Regex("[A-Fa-f0-9-]{8,64}"))) return@parseRecord null
                    if (runCatching { SpiderToolId.valueOf(tool) }.isFailure) return@parseRecord null
                    if (disposition !in setOf("ALLOW", "REQUIRE_APPROVAL", "DENY")) return@parseRecord null
                    val safeCode = code.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: "event_recorded"
                    SpiderAuditReference(
                        referenceId = eventId.take(8),
                        timestampEpochMs = json.optLong("timestampEpochMs", 0L).coerceAtLeast(0L),
                        toolId = tool,
                        disposition = disposition,
                        resultCode = safeCode
                    )
                }.getOrNull()
            }.sortedByDescending { it.timestampEpochMs }.take(limit).toList()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun append(
        taskId: String,
        requestId: String,
        toolId: SpiderToolId,
        decision: SpiderPolicyDecision,
        resultCode: String,
        summary: String
    ): Boolean = runCatching {
        check(taskId.isNotBlank() && requestId.isNotBlank()) { "Audit identifiers cannot be empty" }
        check(logFile.parentFile?.mkdirs() != false || logFile.parentFile?.isDirectory == true) { "Cannot create audit directory" }
        val record = JSONObject()
            .put("eventId", UUID.randomUUID().toString())
            .put("timestampEpochMs", System.currentTimeMillis())
            .put("taskId", taskId.take(120))
            .put("requestId", requestId.take(120))
            .put("toolId", toolId.name)
            .put("risk", decision.risk.name)
            .put("disposition", decision.disposition.name)
            .put("reasonCode", decision.reasonCode.take(80))
            .put("resultCode", SpiderAuditRedactor.redact(resultCode).take(80))
            .put("summary", SpiderAuditRedactor.redact(summary).take(400))
        logFile.appendText(record.toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)


    private companion object {
        const val MAX_SCAN_BYTES = 512L * 1024L
    }
}

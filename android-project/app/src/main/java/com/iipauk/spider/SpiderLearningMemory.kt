package com.iipauk.spider

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * Legacy binary experience-memory format retained for migration and compatibility tests.
 * The production Android runtime uses SqliteSpiderLearningMemory; the byte ceiling below applies
 * only when reading/writing this legacy single-file format, not to the active SQLite store.
 *
 * This is memory-based adaptation, not model-weight training. It stores only topic tokens,
 * typed tool IDs, coarse outcome codes and explicit user feedback. It never stores full prompts,
 * model transcripts, file contents, raw tool output, credentials or free-form reflections.
 */
enum class SpiderLearningOutcome { VERIFIED_SUCCESS, FAILED, UNKNOWN, CANCELLED }
enum class SpiderLearningFeedback { HELPFUL, NOT_HELPFUL }

data class SpiderLearningExperience(
    val taskId: String,
    val updatedAtEpochMs: Long,
    val topicTokens: List<String>,
    val outcome: SpiderLearningOutcome,
    val errorCode: String?,
    val toolIds: List<SpiderToolId>,
    val verified: Boolean,
    val feedback: SpiderLearningFeedback? = null,
    /** App-private workspace identifier; never a filesystem path. */
    val scopeId: String = "global"
)

data class SpiderLearningHint(
    val kind: String,
    val topicTokens: List<String>,
    val toolIds: List<String>,
    val errorCode: String?,
    val confidenceScore: Int
)

class FileSpiderLearningMemory(private val storeFile: File, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val canonicalPath = storeFile.canonicalFile.absolutePath
    private val lock = locks.computeIfAbsent(canonicalPath) { Any() }

    @Volatile var recoveryNotice: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    /** Capture a terminal task once. Re-observing it updates outcome metadata but preserves feedback. */
    fun observeTerminalTask(task: SpiderAgentTask) = synchronized(lock) {
        if (task.status !in TERMINAL_STATUSES) return@synchronized
        val existing = readLocked()[task.id]
        val tokens = topicTokens(task.goal)
        if (tokens.isEmpty()) {
            lastError = "Для этой задачи не удалось безопасно выделить тематические слова; она не добавлена в память."
            return@synchronized
        }
        val outcome = when (task.status) {
            SpiderTaskStatus.COMPLETED -> SpiderLearningOutcome.VERIFIED_SUCCESS
            SpiderTaskStatus.CANCELLED -> SpiderLearningOutcome.CANCELLED
            SpiderTaskStatus.FAILED -> if (task.lastErrorCode in UNCERTAIN_ERROR_CODES) SpiderLearningOutcome.UNKNOWN else SpiderLearningOutcome.FAILED
            else -> return@synchronized
        }
        val candidate = SpiderLearningExperience(
            taskId = task.id.take(MAX_ID_CHARS),
            updatedAtEpochMs = maxOf(task.updatedAtEpochMs, existing?.updatedAtEpochMs ?: 0L),
            topicTokens = tokens,
            outcome = outcome,
            errorCode = safeErrorCode(task.lastErrorCode),
            toolIds = when (outcome) {
                    SpiderLearningOutcome.VERIFIED_SUCCESS -> task.steps.filter { it.status == SpiderStepStatus.SUCCEEDED }.mapNotNull { it.toolId }.distinct().take(MAX_TOOL_IDS)
                    SpiderLearningOutcome.FAILED -> task.steps.lastOrNull { it.status == SpiderStepStatus.FAILED }?.toolId?.let(::listOf).orEmpty().take(MAX_TOOL_IDS)
                    SpiderLearningOutcome.UNKNOWN -> task.steps.lastOrNull { it.status == SpiderStepStatus.FAILED || it.status == SpiderStepStatus.RUNNING }?.toolId?.let(::listOf).orEmpty().take(MAX_TOOL_IDS)
                    SpiderLearningOutcome.CANCELLED -> emptyList()
                },
            verified = outcome == SpiderLearningOutcome.VERIFIED_SUCCESS,
            feedback = existing?.feedback,
            scopeId = task.workspaceScopeId ?: existing?.scopeId ?: GLOBAL_SCOPE
        )
        val all = readLocked()
        all[task.id] = candidate
        writeLocked(all.values.sortedByDescending { it.updatedAtEpochMs })
        lastError = null
    }

    /**
     * Explicit user feedback may evaluate a plan before execution. Such a record stays UNKNOWN and
     * can never be promoted to verified success without a later independently verified terminal task.
     */
    fun recordFeedback(task: SpiderAgentTask, helpful: Boolean): Boolean = synchronized(lock) {
        val all = readLocked()
        val existing = all[task.id]
        val tokens = existing?.topicTokens ?: topicTokens(task.goal)
        if (tokens.isEmpty()) return@synchronized false
        val outcome = existing?.outcome ?: when (task.status) {
            SpiderTaskStatus.COMPLETED -> SpiderLearningOutcome.VERIFIED_SUCCESS
            SpiderTaskStatus.FAILED -> if (task.lastErrorCode in UNCERTAIN_ERROR_CODES) SpiderLearningOutcome.UNKNOWN else SpiderLearningOutcome.FAILED
            SpiderTaskStatus.CANCELLED -> SpiderLearningOutcome.CANCELLED
            else -> SpiderLearningOutcome.UNKNOWN
        }
        val item = SpiderLearningExperience(
            taskId = task.id.take(MAX_ID_CHARS),
            updatedAtEpochMs = clock(),
            topicTokens = tokens,
            outcome = outcome,
            errorCode = existing?.errorCode ?: safeErrorCode(task.lastErrorCode),
            toolIds = (existing?.toolIds ?: task.steps.filter { it.status == SpiderStepStatus.SUCCEEDED || it.status == SpiderStepStatus.FAILED }
                    .mapNotNull { it.toolId }.distinct()).take(MAX_TOOL_IDS),
            verified = existing?.verified ?: (outcome == SpiderLearningOutcome.VERIFIED_SUCCESS),
            feedback = if (helpful) SpiderLearningFeedback.HELPFUL else SpiderLearningFeedback.NOT_HELPFUL,
            scopeId = existing?.scopeId ?: task.workspaceScopeId ?: GLOBAL_SCOPE
        )
        all[task.id] = item
        writeLocked(all.values.sortedByDescending { it.updatedAtEpochMs })
        lastError = null
        true
    }

    /** Update feedback only for an experience already in memory. */
    fun recordFeedback(taskId: String, helpful: Boolean): Boolean = synchronized(lock) {
        val all = readLocked()
        val item = all[taskId] ?: return@synchronized false
        all[taskId] = item.copy(
            feedback = if (helpful) SpiderLearningFeedback.HELPFUL else SpiderLearningFeedback.NOT_HELPFUL,
            updatedAtEpochMs = clock()
        )
        writeLocked(all.values.sortedByDescending { it.updatedAtEpochMs })
        lastError = null
        true
    }

    /** Hints contain enum metadata and topic tokens only—never a free-form prior response or prompt.
     * Retrieval uses an offline vector-feature encoder with multilingual concept groups and a
     * lexical tie-breaker. It never transmits topic tokens to a server. */
    fun relevantHints(
        goal: String,
        limit: Int = MAX_HINTS,
        scopeId: String? = null,
        queryEmbedding: SpiderDenseEmbedding? = null,
        semanticIndex: Map<String, SpiderDenseEmbedding> = emptyMap()
    ): List<SpiderLearningHint> = synchronized(lock) {
        val queryTokens = topicTokens(goal).toSet()
        if (queryTokens.isEmpty()) return@synchronized emptyList()
        val boundedLimit = limit.coerceIn(0, MAX_HINTS)
        val requestedScope = scopeId?.takeIf { it.matches(SCOPE_ID_REGEX) }
        readLocked().values.asSequence()
            // Global experiences are reusable, but a specific workspace never sees another
            // workspace's project history. Invalid/blank scope is treated as global-only.
            .filter { item -> if (requestedScope == null) item.scopeId == GLOBAL_SCOPE else item.scopeId == GLOBAL_SCOPE || item.scopeId == requestedScope }
            .filter { it.outcome != SpiderLearningOutcome.CANCELLED && it.topicTokens.isNotEmpty() }
            .mapNotNull { item ->
                val localSimilarity = SpiderSemanticSearch.similarity(queryTokens.toList(), item.topicTokens)
                val storedEmbedding = queryEmbedding?.let { semanticIndex[item.taskId]?.takeIf { saved -> saved.modelId == it.modelId } }
                val denseSimilarity = if (queryEmbedding != null && storedEmbedding != null) {
                    cosineSimilarity(queryEmbedding, storedEmbedding)?.coerceAtLeast(0.0)
                } else null
                // If neural embeddings are available from the explicitly enabled provider, they lead;
                // offline concept-vector retrieval remains the fallback for unindexed episodes.
                val similarity = denseSimilarity?.let { (0.82 * it + 0.18 * localSimilarity).coerceIn(0.0, 1.0) } ?: localSimilarity
                if (similarity < MIN_SIMILARITY) return@mapNotNull null
                val confidence = when (item.outcome) {
                    SpiderLearningOutcome.VERIFIED_SUCCESS -> when (item.feedback) {
                        SpiderLearningFeedback.HELPFUL -> 96
                        SpiderLearningFeedback.NOT_HELPFUL -> 12
                        null -> if (item.verified) 78 else 55
                    }
                    SpiderLearningOutcome.FAILED -> if (item.feedback == SpiderLearningFeedback.NOT_HELPFUL) 25 else 42
                    SpiderLearningOutcome.UNKNOWN -> when (item.feedback) {
                        SpiderLearningFeedback.HELPFUL -> 62
                        SpiderLearningFeedback.NOT_HELPFUL -> 8
                        null -> 18
                    }
                    SpiderLearningOutcome.CANCELLED -> 0
                }
                // Prefer recent outcomes, but do not erase older useful knowledge abruptly.
                // Recency affects ranking, never verification status or permission decisions.
                val relevanceScore = similarity * confidence * recencyWeight(item.updatedAtEpochMs)
                item to relevanceScore
            }
            .sortedWith(compareByDescending<Pair<SpiderLearningExperience, Double>> { it.second }
                .thenByDescending { it.first.updatedAtEpochMs })
            .take(boundedLimit)
            .map { (item, _) ->
                SpiderLearningHint(
                    kind = hintKind(item),
                    topicTokens = item.topicTokens.take(MAX_TOPIC_TOKENS),
                    toolIds = item.toolIds.map { it.name }.take(MAX_TOOL_IDS),
                    errorCode = item.errorCode,
                    confidenceScore = confidenceFor(item)
                )
            }
            .toList()
    }

    /** User-facing sanitized summary for view/export UI; no raw goal, content, paths or transcript. */
    fun listExperiences(limit: Int = 50): List<SpiderLearningExperience> = synchronized(lock) {
        readLocked().values.sortedByDescending { it.updatedAtEpochMs }.take(limit.coerceAtLeast(0))
    }

    fun count(): Int = synchronized(lock) { readLocked().size }

    /** Minimal sanitized text suitable for optional embedding calls; never returns a full user prompt. */
    fun safeTopicText(rawGoal: String): String = topicTokens(rawGoal).joinToString(" ").take(MAX_TOPIC_TOKENS * (MAX_TOKEN_CHARS + 1))

    /** Delete a single episode without changing the task-history ledger. */
    fun forget(taskId: String): Boolean = synchronized(lock) {
        val all = readLocked()
        if (all.remove(taskId) == null) return@synchronized false
        writeLocked(all.values.sortedByDescending { it.updatedAtEpochMs })
        lastError = null
        true
    }

    fun clearAll() = synchronized(lock) {
        if (storeFile.exists() && !storeFile.delete()) throw IOException("Не удалось удалить память ИИ Паука.")
        File(storeFile.parentFile, storeFile.name + ".tmp").delete()
        val backups = storeFile.parentFile?.listFiles()?.filter { it.name.startsWith(storeFile.name + ".corrupt-") }.orEmpty()
        backups.forEach { backup -> if (backup.exists() && !backup.delete()) throw IOException("Не удалось удалить резервную копию памяти ИИ Паука.") }
        recoveryNotice = null
        lastError = null
    }

    /**
     * Streams the legacy binary store in bounded batches for a one-time SQLite migration.
     * Unlike listExperiences(), this does not materialize the whole legacy corpus in RAM.
     * The source file is never modified by this method; migration callers can safely retry.
     */
    fun forEachMigrationBatch(batchSize: Int = 250, consume: (List<SpiderLearningExperience>) -> Unit) = synchronized(lock) {
        require(batchSize in 1..2_000) { "Migration batch size is outside the safe range." }
        if (!storeFile.exists()) return@synchronized
        val length = storeFile.length()
        if (length <= 0L || length > MAX_LEGACY_STORE_BYTES) {
            throw IOException("Legacy learning memory size is invalid for import; the original file was preserved.")
        }
        DataInputStream(BufferedInputStream(FileInputStream(storeFile))).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Legacy learning memory signature is invalid; original preserved.")
            val schema = input.readInt()
            if (schema !in MIN_SUPPORTED_SCHEMA..SCHEMA_VERSION) throw IOException("Legacy learning memory schema is unsupported; original preserved.")
            val count = input.readInt()
            if (count < 0 || count.toLong() > length / MIN_BYTES_PER_EXPERIENCE + 1L) {
                throw IOException("Legacy learning memory count is inconsistent; original preserved.")
            }
            val batch = ArrayList<SpiderLearningExperience>(batchSize)
            repeat(count) {
                val taskId = readString(input, MAX_ID_CHARS)
                val timestamp = input.readLong()
                val tokenCount = input.readInt()
                if (tokenCount !in 1..MAX_TOPIC_TOKENS) throw IOException("Legacy topic token count is invalid; original preserved.")
                val tokens = List(tokenCount) { readString(input, MAX_TOKEN_CHARS) }
                if (tokens.any { !isSafeToken(it) }) throw IOException("Legacy topic token is invalid; original preserved.")
                val outcome = runCatching { enumValue<SpiderLearningOutcome>(readString(input, 40)) }
                    .getOrElse { throw IOException("Legacy outcome is invalid; original preserved.", it) }
                val error = readNullableString(input, MAX_ERROR_CHARS)?.let(::safeErrorCode)
                val toolCount = input.readInt()
                if (toolCount !in 0..MAX_TOOL_IDS) throw IOException("Legacy tool count is invalid; original preserved.")
                val tools = List(toolCount) {
                    runCatching { enumValue<SpiderToolId>(readString(input, 60)) }
                        .getOrElse { throw IOException("Legacy tool ID is invalid; original preserved.", it) }
                }.distinct()
                val verified = input.readBoolean()
                val feedback = readNullableString(input, 40)?.let {
                    runCatching { enumValue<SpiderLearningFeedback>(it) }
                        .getOrElse { failure -> throw IOException("Legacy feedback is invalid; original preserved.", failure) }
                }
                val scope = if (schema >= 2) readString(input, MAX_SCOPE_CHARS) else GLOBAL_SCOPE
                if (scope != GLOBAL_SCOPE && !scope.matches(SCOPE_ID_REGEX)) throw IOException("Legacy scope is invalid; original preserved.")
                if (taskId.isBlank()) throw IOException("Legacy task ID is blank; original preserved.")
                batch += SpiderLearningExperience(taskId, timestamp, tokens, outcome, error, tools, verified, feedback, scope)
                if (batch.size >= batchSize) { consume(batch.toList()); batch.clear() }
            }
            if (input.read() != -1) throw IOException("Legacy memory has trailing data; original preserved.")
            if (batch.isNotEmpty()) consume(batch.toList())
        }
    }

    private fun readLocked(): LinkedHashMap<String, SpiderLearningExperience> {
        if (!storeFile.exists()) return linkedMapOf()
        if (storeFile.length() <= 0L) return recoverCorruptLocked("Learning memory file is empty.")
        if (storeFile.length() > MAX_LEGACY_STORE_BYTES) {
            throw IOException("Память опыта превысила безопасный размер чтения; исходный файл сохранён без изменений.")
        }
        try {
            DataInputStream(BufferedInputStream(FileInputStream(storeFile))).use { input ->
                if (input.readInt() != MAGIC) throw InvalidLearningStoreException("Learning memory signature is invalid.")
                val schema = input.readInt()
                if (schema !in MIN_SUPPORTED_SCHEMA..SCHEMA_VERSION) throw UnsupportedLearningSchemaException("Unsupported learning memory schema.")
                val count = input.readInt()
                // There is deliberately no 500-episode cap. Sanity-check the declared count against
                // the actual file length to reject corrupt headers without limiting normal growth.
                if (count < 0 || count.toLong() > (storeFile.length() / MIN_BYTES_PER_EXPERIENCE) + 1L) {
                    throw InvalidLearningStoreException("Learning memory item count is inconsistent with file size.")
                }
                val result = linkedMapOf<String, SpiderLearningExperience>()
                repeat(count) {
                    val taskId = readString(input, MAX_ID_CHARS)
                    val timestamp = input.readLong()
                    val tokenCount = input.readInt()
                    if (tokenCount !in 1..MAX_TOPIC_TOKENS) throw InvalidLearningStoreException("Learning topic token count is invalid.")
                    val tokens = List(tokenCount) { readString(input, MAX_TOKEN_CHARS) }
                    if (tokens.any { !isSafeToken(it) }) throw InvalidLearningStoreException("Learning topic token is invalid.")
                    val outcome = enumValue<SpiderLearningOutcome>(readString(input, 40))
                    val error = readNullableString(input, MAX_ERROR_CHARS)?.let(::safeErrorCode)
                    val toolCount = input.readInt()
                    if (toolCount !in 0..MAX_TOOL_IDS) throw InvalidLearningStoreException("Learning tool count is invalid.")
                    val tools = List(toolCount) { enumValue<SpiderToolId>(readString(input, 60)) }.distinct()
                    val verified = input.readBoolean()
                    val feedback = readNullableString(input, 40)?.let { enumValue<SpiderLearningFeedback>(it) }
                    val scope = if (schema >= 2) readString(input, MAX_SCOPE_CHARS) else GLOBAL_SCOPE
                if (scope != GLOBAL_SCOPE && !scope.matches(SCOPE_ID_REGEX)) throw InvalidLearningStoreException("Learning scope ID is invalid.")
                val item = SpiderLearningExperience(taskId, timestamp, tokens, outcome, error, tools, verified, feedback, scope)
                    if (taskId.isBlank() || result.put(taskId, item) != null) throw InvalidLearningStoreException("Duplicate or blank task ID in memory.")
                }
                if (input.read() != -1) throw InvalidLearningStoreException("Unexpected trailing bytes in memory store.")
                return result
            }
        } catch (unsupported: UnsupportedLearningSchemaException) {
            throw IOException(unsupported.message ?: "Learning memory schema unsupported.", unsupported)
        } catch (invalid: InvalidLearningStoreException) {
            return recoverCorruptLocked(invalid.message ?: "Learning memory data is invalid.")
        } catch (_: EOFException) {
            return recoverCorruptLocked("Learning memory file is truncated.")
        } catch (_: IllegalArgumentException) {
            return recoverCorruptLocked("Learning memory contains an invalid field.")
        }
    }

    private fun recoverCorruptLocked(reason: String): LinkedHashMap<String, SpiderLearningExperience> {
        val backup = File(storeFile.parentFile, "${storeFile.name}.corrupt-${clock()}")
        try {
            Files.move(storeFile.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            if (!storeFile.renameTo(backup)) throw IOException("$reason Original memory could not be preserved; refusing reset.")
        } catch (e: IOException) {
            throw IOException("$reason Original memory could not be preserved; refusing reset.", e)
        }
        recoveryNotice = "$reason A backup was preserved as '${backup.name}'."
        return linkedMapOf()
    }

    private fun writeLocked(items: List<SpiderLearningExperience>) {
        val parent = storeFile.absoluteFile.parentFile ?: throw IOException("Learning memory must be app-private.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create learning memory directory.")
        if (!parent.isDirectory) throw IOException("Learning memory parent is not a directory.")
        val temp = File(parent, storeFile.name + ".tmp")
        try {
            val stream = FileOutputStream(temp, false)
            DataOutputStream(BufferedOutputStream(stream)).use { out ->
                out.writeInt(MAGIC); out.writeInt(SCHEMA_VERSION); out.writeInt(items.size)
                items.forEach { item ->
                    writeString(out, item.taskId, MAX_ID_CHARS)
                    out.writeLong(item.updatedAtEpochMs)
                    val tokens = item.topicTokens.filter(::isSafeToken).distinct().take(MAX_TOPIC_TOKENS)
                    require(tokens.isNotEmpty()) { "Learning experience requires topic tokens" }
                    out.writeInt(tokens.size); tokens.forEach { writeString(out, it, MAX_TOKEN_CHARS) }
                    writeString(out, item.outcome.name, 40)
                    writeNullableString(out, safeErrorCode(item.errorCode), MAX_ERROR_CHARS)
                    val tools = item.toolIds.distinct().take(MAX_TOOL_IDS)
                    out.writeInt(tools.size); tools.forEach { writeString(out, it.name, 60) }
                    out.writeBoolean(item.verified)
                    writeNullableString(out, item.feedback?.name, 40)
                    val scope = item.scopeId.takeIf { it == GLOBAL_SCOPE || it.matches(SCOPE_ID_REGEX) }
                        ?: throw IllegalArgumentException("Learning scope ID is invalid")
                    writeString(out, scope, MAX_SCOPE_CHARS)
                }
                out.flush(); stream.fd.sync()
            }
            if (temp.length() > MAX_LEGACY_STORE_BYTES) {
                throw IOException("Память опыта достигла безопасного лимита размера; старые эпизоды не удалены.")
            }
            try {
                Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        }
    }

    private fun topicTokens(raw: String): List<String> {
        val scrubbed = raw
            .replace(Regex("(?i)\\b(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9_]{12,}|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b"), " ")
            .replace(Regex("\\b[A-Fa-f0-9]{32,}\\b"), " ")
            .replace(Regex("(?i)\\b(?:https?|file|content|smb|rtsp)://[^\\s]+"), " ")
            .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?i)\\b(?:api[_-]?key|token|secret|password|пароль|токен|ключ)\\s*[:=]?\\s*[^\\s,;]+"), " ")
            .replace(Regex("(?i)(?:/data/|/storage/|/sdcard/|/home/|/Users/)[^\\s,;]+"), " ")
            .replace(Regex("(?i)\\b[A-Z]:\\\\[^\\s,;]+"), " ")
        return TOKEN_REGEX.findAll(scrubbed.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS && isSafeToken(it) }
            .distinct().take(MAX_TOPIC_TOKENS).toList()
    }

    private fun recencyWeight(updatedAtEpochMs: Long): Double {
        val ageMs = (clock() - updatedAtEpochMs).coerceAtLeast(0L)
        val ageDays = ageMs / MILLIS_PER_DAY
        return when {
            ageDays <= 7 -> 1.0
            ageDays <= 30 -> 0.94
            ageDays <= 90 -> 0.84
            ageDays <= 365 -> 0.70
            else -> 0.55
        }
    }

    private fun confidenceFor(item: SpiderLearningExperience): Int = when (item.outcome) {
        SpiderLearningOutcome.VERIFIED_SUCCESS -> when (item.feedback) {
            SpiderLearningFeedback.HELPFUL -> 96
            SpiderLearningFeedback.NOT_HELPFUL -> 12
            null -> if (item.verified) 78 else 55
        }
        SpiderLearningOutcome.FAILED -> if (item.feedback == SpiderLearningFeedback.NOT_HELPFUL) 25 else 42
        SpiderLearningOutcome.UNKNOWN -> when (item.feedback) {
            SpiderLearningFeedback.HELPFUL -> 62 // a user-rated plan is evidence about usefulness, not verified execution
            SpiderLearningFeedback.NOT_HELPFUL -> 8
            null -> 18
        }
        SpiderLearningOutcome.CANCELLED -> 0
    }

    private fun hintKind(item: SpiderLearningExperience): String = when {
        item.outcome == SpiderLearningOutcome.VERIFIED_SUCCESS && item.feedback == SpiderLearningFeedback.HELPFUL -> "user_endorsed_success"
        item.outcome == SpiderLearningOutcome.VERIFIED_SUCCESS && item.feedback == SpiderLearningFeedback.NOT_HELPFUL -> "user_disliked_success"
        item.outcome == SpiderLearningOutcome.VERIFIED_SUCCESS -> "verified_success"
        item.outcome == SpiderLearningOutcome.FAILED -> "prior_failure"
        item.outcome == SpiderLearningOutcome.UNKNOWN && item.feedback == SpiderLearningFeedback.HELPFUL -> "user_endorsed_plan"
        item.outcome == SpiderLearningOutcome.UNKNOWN && item.feedback == SpiderLearningFeedback.NOT_HELPFUL -> "user_disliked_plan"
        else -> "uncertain_outcome"
    }

    private fun safeErrorCode(raw: String?): String? = raw?.takeIf { it in SAFE_ERROR_CODES }

    private fun isSafeToken(token: String): Boolean = token.length in 3..MAX_TOKEN_CHARS &&
        token.matches(Regex("[a-zа-яё_-]+")) && token !in SENSITIVE_TOKENS && token !in INSTRUCTION_LIKE_TOKENS

    private fun writeString(out: DataOutputStream, value: String, max: Int) {
        require(value.length <= max) { "Learning memory field is too long" }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= max * 4) { "Learning memory field is too large" }
        out.writeInt(bytes.size); out.write(bytes)
    }

    private fun writeNullableString(out: DataOutputStream, value: String?, max: Int) {
        out.writeBoolean(value != null); value?.let { writeString(out, it, max) }
    }

    private fun readString(input: DataInputStream, max: Int): String {
        val length = input.readInt()
        if (length !in 0..max * 4) throw InvalidLearningStoreException("Learning field size is invalid.")
        val bytes = ByteArray(length); input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8).also { if (it.length > max) throw InvalidLearningStoreException("Learning field exceeds limit.") }
    }

    private fun readNullableString(input: DataInputStream, max: Int): String? = if (input.readBoolean()) readString(input, max) else null
    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf<T>(value)

    companion object {
        private const val MAGIC = 0x53504C4D // SPLM
        private const val SCHEMA_VERSION = 2
        private const val MIN_SUPPORTED_SCHEMA = 1
        private const val MAX_SCOPE_CHARS = 120
        private const val GLOBAL_SCOPE = "global"
        private val SCOPE_ID_REGEX = Regex("[A-Za-z0-9_.-]{1,120}")
        const val MAX_HINTS = 5
        const val MAX_TOPIC_TOKENS = 12
        const val MAX_TOOL_IDS = 5
        private const val MAX_ID_CHARS = 120
        private const val MAX_TOKEN_CHARS = 32
        private const val MAX_ERROR_CHARS = 80
        // Storage is bounded by bytes, not by episode count. No automatic oldest-record eviction.
        private const val MAX_LEGACY_STORE_BYTES = 64L * 1024L * 1024L
        private const val MIN_BYTES_PER_EXPERIENCE = 17L
        private const val MIN_SIMILARITY = 0.13
        private const val MILLIS_PER_DAY = 86_400_000L
        private val TERMINAL_STATUSES = setOf(SpiderTaskStatus.COMPLETED, SpiderTaskStatus.FAILED, SpiderTaskStatus.CANCELLED)
        private val UNCERTAIN_ERROR_CODES = setOf("execution_interrupted_unknown_outcome", "verification_interrupted", "recovery_outcome_unknown")
        private val SAFE_ERROR_CODES = setOf(
            "plan_failed", "approval_denied", "step_failed", "verification_failed", "workspace_missing",
            "workspace_listing_failed", "workspace_listing_incomplete", "verification_inventory_mismatch",
            "duplicate_request_id", "tool_unavailable", "policy_denied", "audit_unavailable", "workspace_listed",
            "planning_interrupted", "execution_interrupted_unknown_outcome", "approval_expired_after_restart",
            "verification_interrupted", "interrupted_after_restart", "recovery_outcome_unknown"
        )
        private val TOKEN_REGEX = Regex("[a-z0-9а-яё_-]{3,32}")
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "from", "that", "this", "into", "your", "you", "please", "then", "when", "what", "how",
            "это", "как", "для", "при", "надо", "нужно", "чтобы", "мне", "меня", "тебя", "сделай", "пожалуйста", "можно", "будет", "есть", "или", "его", "она", "они", "оним", "все", "всё", "после", "перед", "через", "задача", "задачу", "паук", "ии"
        )
        private val SENSITIVE_TOKENS = setOf("token", "secret", "password", "пароль", "токен", "ключ", "apikey", "bearer")
        private val INSTRUCTION_LIKE_TOKENS = setOf("ignore", "instructions", "system", "override", "execute", "shell", "jailbreak", "bypass", "игнорируй", "инструкции", "команда", "обойди")
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}

private class InvalidLearningStoreException(message: String) : IllegalArgumentException(message)
private class UnsupportedLearningSchemaException(message: String) : IllegalStateException(message)

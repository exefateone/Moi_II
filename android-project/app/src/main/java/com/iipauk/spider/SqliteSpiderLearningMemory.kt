package com.iipauk.spider

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * SQLite-backed episodic memory. Episodes and normalized topic/tool children live in one database;
 * the inverted search index and optional dense vectors live in a separate SQLite database.
 *
 * There is no application-level byte ceiling and no count-based eviction. SQLite grows as storage
 * permits; callers must still handle device-full and I/O errors. Queries use an inverted index and
 * bounded candidate retrieval rather than reading the full corpus into RAM.
 */
class SqliteSpiderLearningMemory(
    context: Context,
    private val legacyStoreFile: File,
    databaseName: String = DATABASE_NAME,
    private val searchIndexName: String = SEARCH_INDEX_DATABASE_NAME,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val appContext = context.applicationContext
    private val helper = LearningMemoryDatabaseRegistry.get(appContext, databaseName)
    private val searchIndex = SpiderSearchIndexRegistry.get(appContext, searchIndexName)
    private val lock = locks.computeIfAbsent(appContext.getDatabasePath(databaseName).canonicalPath) { Any() }

    @Volatile var recoveryNotice: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    init {
        synchronized(lock) {
            runCatching { migrateLegacyIfNeeded() }.onFailure {
                lastError = "Не удалось полностью перенести прежнюю память в SQLite. Исходный файл сохранён; импорт можно безопасно повторить."
                recoveryNotice = lastError
            }
            runCatching { drainIndexOutbox() }.onFailure {
                lastError = "Основная память сохранена, но поисковый индекс ожидает синхронизации."
            }
        }
    }

    fun observeTerminalTask(task: SpiderAgentTask) = synchronized(lock) {
        if (task.status !in TERMINAL_STATUSES) return@synchronized
        try {
            val db = helper.writableDatabase
            val existing = readEpisode(db, task.id)
            val tokens = topicTokens(task.goal)
            if (tokens.isEmpty()) {
                lastError = "Не удалось безопасно выделить тематические слова; задача не добавлена в память."
                return@synchronized
            }
            val outcome = when (task.status) {
                SpiderTaskStatus.COMPLETED -> SpiderLearningOutcome.VERIFIED_SUCCESS
                SpiderTaskStatus.CANCELLED -> SpiderLearningOutcome.CANCELLED
                SpiderTaskStatus.FAILED -> if (task.lastErrorCode in UNCERTAIN_ERROR_CODES) SpiderLearningOutcome.UNKNOWN else SpiderLearningOutcome.FAILED
                else -> return@synchronized
            }
            val item = SpiderLearningExperience(
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
            saveEpisode(item, replace = true)
            flushAfterWrite()
        } catch (_: Exception) {
            markStorageFailure("сохранение результата задачи")
        }
    }

    fun recordFeedback(task: SpiderAgentTask, helpful: Boolean): Boolean = synchronized(lock) {
        try {
            val existing = readEpisode(helper.readableDatabase, task.id)
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
            saveEpisode(item, replace = true)
            flushAfterWrite()
            lastError == null
        } catch (_: Exception) {
            markStorageFailure("сохранение оценки пользователя")
            false
        }
    }

    fun recordFeedback(taskId: String, helpful: Boolean): Boolean = synchronized(lock) {
        try {
            val existing = readEpisode(helper.readableDatabase, taskId) ?: return@synchronized false
            saveEpisode(existing.copy(
                feedback = if (helpful) SpiderLearningFeedback.HELPFUL else SpiderLearningFeedback.NOT_HELPFUL,
                updatedAtEpochMs = clock()
            ), replace = true)
            flushAfterWrite()
            lastError == null
        } catch (_: Exception) {
            markStorageFailure("сохранение оценки пользователя")
            false
        }
    }

    fun relevantHints(
        goal: String,
        limit: Int = MAX_HINTS,
        scopeId: String? = null,
        queryEmbedding: SpiderDenseEmbedding? = null,
        semanticIndex: Map<String, SpiderDenseEmbedding> = emptyMap(),
        additionalCandidateTaskIds: Set<String> = emptySet()
    ): List<SpiderLearningHint> = synchronized(lock) {
        val queryTokens = topicTokens(goal)
        if (queryTokens.isEmpty()) return@synchronized emptyList()
        val requestedScope = scopeId?.takeIf { it.matches(SCOPE_ID_REGEX) }
        val boundedLimit = limit.coerceIn(0, MAX_HINTS)
        if (boundedLimit == 0) return@synchronized emptyList()
        val indexFresh = runCatching { drainIndexOutbox(); true }.getOrElse {
            lastError = "Поисковый индекс временно недоступен; использован ограниченный резервный поиск."
            false
        }
        val candidateIds = linkedSetOf<String>()
        if (indexFresh) {
            runCatching { searchIndex.candidateTaskIds(queryTokens, requestedScope, 400) }
                .onSuccess(candidateIds::addAll)
                .onFailure { lastError = "Не удалось прочитать поисковый индекс; использован резервный поиск." }
        }
        candidateIds.addAll(additionalCandidateTaskIds.filter { it.matches(ID_REGEX) }.take(MAX_RETRIEVAL_CANDIDATES))
        if (candidateIds.isEmpty()) {
            // Conservative fallback is bounded: a failed index never causes a full-corpus RAM scan.
            candidateIds.addAll(listExperiencesPage(0, FALLBACK_CANDIDATES).map { it.taskId })
        }
        val items = loadExperiencesByIds(candidateIds.toList()).asSequence()
            .filter { item -> if (requestedScope == null) item.scopeId == GLOBAL_SCOPE else item.scopeId == GLOBAL_SCOPE || item.scopeId == requestedScope }
            .filter { it.outcome != SpiderLearningOutcome.CANCELLED && it.topicTokens.isNotEmpty() }
            .mapNotNull { item ->
                val localSimilarity = SpiderSemanticSearch.similarity(queryTokens, item.topicTokens)
                val saved = if (queryEmbedding == null) null else semanticIndex[item.taskId]?.takeIf { it.modelId == queryEmbedding.modelId }
                val denseSimilarity = if (queryEmbedding != null && saved != null) cosineSimilarity(queryEmbedding, saved)?.coerceAtLeast(0.0) else null
                val similarity = denseSimilarity?.let { (0.82 * it + 0.18 * localSimilarity).coerceIn(0.0, 1.0) } ?: localSimilarity
                if (similarity < MIN_SIMILARITY) return@mapNotNull null
                val score = similarity * confidenceFor(item) * recencyWeight(item.updatedAtEpochMs)
                item to score
            }
            .sortedWith(compareByDescending<Pair<SpiderLearningExperience, Double>> { it.second }.thenByDescending { it.first.updatedAtEpochMs })
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
        items
    }

    fun candidateTaskIds(goal: String, scopeId: String? = null, limit: Int = MAX_RETRIEVAL_CANDIDATES): List<String> = synchronized(lock) {
        val tokens = topicTokens(goal)
        if (tokens.isEmpty()) return@synchronized emptyList()
        val scope = scopeId?.takeIf { it.matches(SCOPE_ID_REGEX) }
        val indexFresh = runCatching { drainIndexOutbox(); true }.getOrDefault(false)
        if (!indexFresh) return@synchronized listExperiencesPage(0, FALLBACK_CANDIDATES).map { it.taskId }
        runCatching { searchIndex.candidateTaskIds(tokens, scope, limit.coerceIn(1, MAX_RETRIEVAL_CANDIDATES)) }
            .getOrElse { listExperiencesPage(0, FALLBACK_CANDIDATES).map { it.taskId } }
    }

    fun listExperiences(limit: Int = 50): List<SpiderLearningExperience> = listExperiencesPage(0, limit)

    /** Stable, bounded page ordered by newest update first. No hidden cap applies to total rows. */
    fun listExperiencesPage(offset: Int, limit: Int = 100): List<SpiderLearningExperience> = synchronized(lock) {
        val boundedOffset = offset.coerceAtLeast(0)
        val boundedLimit = limit.coerceIn(0, MAX_PAGE_SIZE)
        if (boundedLimit == 0) return@synchronized emptyList()
        val db = helper.readableDatabase
        val ids = mutableListOf<String>()
        db.rawQuery(
            "SELECT task_id FROM learning_experiences ORDER BY updated_at DESC, task_id DESC LIMIT ? OFFSET ?",
            arrayOf(boundedLimit.toString(), boundedOffset.toString())
        ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
        loadExperiencesByIds(ids)
    }

    /** Ascending keyset page for background indexing; this avoids OFFSET cost for deep corpora. */
    fun listExperiencesAfter(updatedAtEpochMs: Long, taskId: String, limit: Int = 100): List<SpiderLearningExperience> = synchronized(lock) {
        val boundedLimit = limit.coerceIn(0, MAX_PAGE_SIZE)
        if (boundedLimit == 0) return@synchronized emptyList()
        val ids = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT task_id FROM learning_experiences WHERE updated_at > ? OR (updated_at = ? AND task_id > ?) ORDER BY updated_at ASC, task_id ASC LIMIT ?",
            arrayOf(updatedAtEpochMs.toString(), updatedAtEpochMs.toString(), taskId, boundedLimit.toString())
        ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
        loadExperiencesByIds(ids)
    }

    fun count(): Int = synchronized(lock) {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM learning_experiences", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun safeTopicText(rawGoal: String): String = topicTokens(rawGoal).joinToString(" ").take(MAX_TOPIC_TOKENS * (MAX_TOKEN_CHARS + 1))

    fun forget(taskId: String): Boolean = synchronized(lock) {
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            val deleted: Int
            try {
                deleted = db.delete("learning_experiences", "task_id = ?", arrayOf(taskId))
                if (deleted > 0) enqueueIndexChange(db, taskId, OP_DELETE)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (deleted > 0) flushAfterWrite()
            deleted > 0
        } catch (_: Exception) {
            markStorageFailure("удаление эпизода памяти")
            false
        }
    }

    fun clearAll() = synchronized(lock) {
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.delete("learning_experiences", null, null)
                enqueueIndexChange(db, "", OP_CLEAR)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            flushAfterWrite()
            if (lastError == null) recoveryNotice = null
        } catch (_: Exception) {
            markStorageFailure("очистка памяти")
        }
    }

    /** Count/page statistics used by diagnostics without loading episode payloads into memory. */
    fun storageStats(): SpiderMemoryStorageStats = synchronized(lock) {
        val db = helper.readableDatabase
        val count = scalarLong(db, "SELECT COUNT(*) FROM learning_experiences")
        val topics = scalarLong(db, "SELECT COUNT(*) FROM learning_topic_tokens")
        val pending = scalarLong(db, "SELECT COUNT(*) FROM memory_index_outbox")
        SpiderMemoryStorageStats(count, topics, pending, physicalDatabaseBytes(helper.databaseNameForDiagnostics()), physicalDatabaseBytes(searchIndexName))
    }

    /**
     * Explicit, read-only diagnostic snapshot. This intentionally exposes counts, byte totals,
     * SQLite health flags and free space only: never file paths, topic tokens, task goals or secrets.
     * PRAGMA quick_check may scan database pages, so call this only on an explicit user request.
     */
    fun diagnosticsSnapshot(): SpiderMemoryDiagnosticsSnapshot = synchronized(lock) {
        val memoryDb = helper.readableDatabase
        val indexDb = searchIndex.readableDatabase
        val fileStats = runCatching {
            val stats = StatFs(appContext.filesDir.absolutePath)
            stats.availableBytes to stats.totalBytes
        }.getOrDefault(0L to 0L)
        SpiderMemoryDiagnosticsSnapshot(
            episodeCount = scalarLong(memoryDb, "SELECT COUNT(*) FROM learning_experiences"),
            topicTokenCount = scalarLong(memoryDb, "SELECT COUNT(*) FROM learning_topic_tokens"),
            pendingIndexChanges = scalarLong(memoryDb, "SELECT COUNT(*) FROM memory_index_outbox"),
            lexicalPostingCount = scalarLong(indexDb, "SELECT COUNT(*) FROM search_postings"),
            embeddingVectorCount = scalarLong(indexDb, "SELECT COUNT(*) FROM dense_embeddings"),
            embeddingModelCount = scalarLong(indexDb, "SELECT COUNT(DISTINCT model_id) FROM dense_embeddings"),
            memoryDatabaseBytes = physicalDatabaseBytes(helper.databaseNameForDiagnostics()),
            searchIndexDatabaseBytes = physicalDatabaseBytes(searchIndexName),
            availableStorageBytes = fileStats.first,
            totalStorageBytes = fileStats.second,
            memorySchemaVersion = memoryDb.version,
            searchSchemaVersion = indexDb.version,
            memoryIntegrity = sqliteQuickCheck(memoryDb),
            searchIndexIntegrity = sqliteQuickCheck(indexDb),
            lastStorageError = lastError,
            recoveryNotice = recoveryNotice
        )
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun physicalDatabaseBytes(name: String): Long = listOf(name, "$name-wal", "$name-shm").sumOf {
        appContext.getDatabasePath(it).length().coerceAtLeast(0L)
    }

    private fun sqliteQuickCheck(db: SQLiteDatabase): String = try {
        val result = db.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "no_result"
        }
        if (result.equals("ok", ignoreCase = true)) "ok" else "integrity_warning"
    } catch (_: Exception) {
        "check_unavailable"
    }

    private fun saveEpisode(item: SpiderLearningExperience, replace: Boolean) {
        validateEpisode(item)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("task_id", item.taskId); put("updated_at", item.updatedAtEpochMs); put("outcome", item.outcome.name)
                put("error_code", safeErrorCode(item.errorCode)); put("verified", if (item.verified) 1 else 0)
                put("feedback", item.feedback?.name); put("scope_id", safeScope(item.scopeId))
            }
            val conflict = if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE
            val inserted = db.insertWithOnConflict("learning_experiences", null, values, conflict)
            if (inserted != -1L || replace) {
                db.delete("learning_topic_tokens", "task_id = ?", arrayOf(item.taskId))
                db.delete("learning_tools", "task_id = ?", arrayOf(item.taskId))
                item.topicTokens.filter(::isSafeToken).distinct().take(MAX_TOPIC_TOKENS).forEachIndexed { index, token ->
                    db.insertOrThrow("learning_topic_tokens", null, ContentValues().apply {
                        put("task_id", item.taskId); put("ordinal", index); put("token", token)
                    })
                }
                item.toolIds.distinct().take(MAX_TOOL_IDS).forEachIndexed { index, tool ->
                    db.insertOrThrow("learning_tools", null, ContentValues().apply {
                        put("task_id", item.taskId); put("ordinal", index); put("tool_id", tool.name)
                    })
                }
                enqueueIndexChange(db, item.taskId, OP_UPSERT)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun migrateLegacyIfNeeded() {
        val db = helper.writableDatabase
        val status = db.rawQuery("SELECT value FROM memory_metadata WHERE key = ?", arrayOf(LEGACY_MIGRATION_KEY)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (status == MIGRATION_DONE) return
        if (!legacyStoreFile.exists()) {
            writeMetadata(LEGACY_MIGRATION_KEY, MIGRATION_DONE)
            return
        }
        val legacy = FileSpiderLearningMemory(legacyStoreFile, clock)
        legacy.forEachMigrationBatch(MIGRATION_BATCH_SIZE) { batch ->
            val writable = helper.writableDatabase
            writable.beginTransaction()
            try {
                batch.forEach { item -> saveEpisodeInTransaction(writable, item, ignoreExisting = true) }
                writable.setTransactionSuccessful()
            } finally { writable.endTransaction() }
            runCatching { drainIndexOutbox() }
        }
        writeMetadata(LEGACY_MIGRATION_KEY, MIGRATION_DONE)
        val archive = File(legacyStoreFile.parentFile, legacyStoreFile.name + ".migrated-v2")
        if (archive.exists()) {
            // A previous successful migration may have archived the source; do not overwrite it.
            recoveryNotice = "SQLite migration completed. A prior legacy archive already exists."
        } else if (!legacyStoreFile.renameTo(archive)) {
            recoveryNotice = "SQLite migration completed, but the original memory file could not be renamed. It was preserved."
        } else {
            recoveryNotice = "Память перенесена в SQLite. Старый файл сохранён как ${archive.name}."
        }
    }

    private fun saveEpisodeInTransaction(db: SQLiteDatabase, item: SpiderLearningExperience, ignoreExisting: Boolean) {
        validateEpisode(item)
        val values = ContentValues().apply {
            put("task_id", item.taskId); put("updated_at", item.updatedAtEpochMs); put("outcome", item.outcome.name)
            put("error_code", safeErrorCode(item.errorCode)); put("verified", if (item.verified) 1 else 0)
            put("feedback", item.feedback?.name); put("scope_id", safeScope(item.scopeId))
        }
        val inserted = db.insertWithOnConflict(
            "learning_experiences", null, values,
            if (ignoreExisting) SQLiteDatabase.CONFLICT_IGNORE else SQLiteDatabase.CONFLICT_REPLACE
        )
        if (inserted == -1L) return
        item.topicTokens.filter(::isSafeToken).distinct().take(MAX_TOPIC_TOKENS).forEachIndexed { index, token ->
            db.insertOrThrow("learning_topic_tokens", null, ContentValues().apply { put("task_id", item.taskId); put("ordinal", index); put("token", token) })
        }
        item.toolIds.distinct().take(MAX_TOOL_IDS).forEachIndexed { index, tool ->
            db.insertOrThrow("learning_tools", null, ContentValues().apply { put("task_id", item.taskId); put("ordinal", index); put("tool_id", tool.name) })
        }
        enqueueIndexChange(db, item.taskId, OP_UPSERT)
    }

    private fun loadExperiencesByIds(ids: List<String>): List<SpiderLearningExperience> {
        if (ids.isEmpty()) return emptyList()
        val db = helper.readableDatabase
        val uniqueIds = ids.distinct().take(MAX_RETRIEVAL_CANDIDATES)
        val byId = linkedMapOf<String, SpiderLearningExperience>()
        uniqueIds.chunked(SQL_BIND_CHUNK).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            db.rawQuery(
                "SELECT task_id, updated_at, outcome, error_code, verified, feedback, scope_id FROM learning_experiences WHERE task_id IN ($placeholders)",
                chunk.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val item = SpiderLearningExperience(
                        taskId = cursor.getString(0), updatedAtEpochMs = cursor.getLong(1),
                        topicTokens = emptyList(), outcome = enumValue<SpiderLearningOutcome>(cursor.getString(2)),
                        errorCode = cursor.getStringOrNull(3), toolIds = emptyList(), verified = cursor.getInt(4) != 0,
                        feedback = cursor.getStringOrNull(5)?.let { enumValue<SpiderLearningFeedback>(it) }, scopeId = cursor.getString(6)
                    )
                    byId[item.taskId] = item
                }
            }
        }
        val loadedTokens = linkedMapOf<String, MutableList<String>>()
        val loadedTools = linkedMapOf<String, MutableList<SpiderToolId>>()
        uniqueIds.chunked(SQL_BIND_CHUNK).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            db.rawQuery("SELECT task_id, token FROM learning_topic_tokens WHERE task_id IN ($placeholders) ORDER BY task_id, ordinal", chunk.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) loadedTokens.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1))
            }
            db.rawQuery("SELECT task_id, tool_id FROM learning_tools WHERE task_id IN ($placeholders) ORDER BY task_id, ordinal", chunk.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val tool = runCatching { enumValue<SpiderToolId>(cursor.getString(1)) }.getOrNull() ?: continue
                    loadedTools.getOrPut(cursor.getString(0)) { mutableListOf() }.add(tool)
                }
            }
        }
        return ids.distinct().mapNotNull { id ->
            val item = byId[id] ?: return@mapNotNull null
            item.copy(topicTokens = loadedTokens[id].orEmpty(), toolIds = loadedTools[id].orEmpty())
        }
    }

    private fun readEpisode(db: SQLiteDatabase, taskId: String): SpiderLearningExperience? {
        val base = db.rawQuery(
            "SELECT task_id, updated_at, outcome, error_code, verified, feedback, scope_id FROM learning_experiences WHERE task_id = ?",
            arrayOf(taskId)
        ).use { cursor -> if (cursor.moveToFirst()) SpiderLearningExperience(
            taskId = cursor.getString(0), updatedAtEpochMs = cursor.getLong(1), topicTokens = emptyList(),
            outcome = enumValue<SpiderLearningOutcome>(cursor.getString(2)), errorCode = cursor.getStringOrNull(3),
            toolIds = emptyList(), verified = cursor.getInt(4) != 0,
            feedback = cursor.getStringOrNull(5)?.let { enumValue<SpiderLearningFeedback>(it) }, scopeId = cursor.getString(6)
        ) else null } ?: return null
        val tokens = mutableListOf<String>()
        db.rawQuery("SELECT token FROM learning_topic_tokens WHERE task_id = ? ORDER BY ordinal", arrayOf(taskId)).use { cursor -> while (cursor.moveToNext()) tokens += cursor.getString(0) }
        val tools = mutableListOf<SpiderToolId>()
        db.rawQuery("SELECT tool_id FROM learning_tools WHERE task_id = ? ORDER BY ordinal", arrayOf(taskId)).use { cursor ->
            while (cursor.moveToNext()) runCatching { enumValue<SpiderToolId>(cursor.getString(0)) }.getOrNull()?.let(tools::add)
        }
        return base.copy(topicTokens = tokens, toolIds = tools)
    }

    private data class IndexChange(val seq: Long, val taskId: String, val operation: String)

    private fun drainIndexOutbox() {
        val primary = helper.writableDatabase
        while (true) {
            val changes = mutableListOf<IndexChange>()
            primary.rawQuery("SELECT seq, task_id, operation FROM memory_index_outbox ORDER BY seq LIMIT ?", arrayOf(OUTBOX_BATCH_SIZE.toString())).use { cursor ->
                while (cursor.moveToNext()) changes += IndexChange(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
            }
            if (changes.isEmpty()) return
            val indexDb = searchIndex.writableDatabase
            indexDb.beginTransaction()
            try {
                changes.forEach { change ->
                    when (change.operation) {
                        OP_CLEAR -> {
                            indexDb.delete("search_postings", null, null)
                            indexDb.delete("dense_embeddings", null, null)
                            indexDb.delete("embedding_backfill_state", null, null)
                        }
                        OP_DELETE -> {
                            indexDb.delete("search_postings", "task_id = ?", arrayOf(change.taskId))
                            indexDb.delete("dense_embeddings", "task_id = ?", arrayOf(change.taskId))
                        }
                        OP_UPSERT -> {
                            indexDb.delete("search_postings", "task_id = ?", arrayOf(change.taskId))
                            val episode = readEpisode(primary, change.taskId)
                            if (episode != null) {
                                val terms = SpiderSemanticSearch.searchTerms(episode.topicTokens)
                                terms.forEach { term -> indexDb.insertWithOnConflict("search_postings", null, ContentValues().apply {
                                    put("term", term); put("task_id", episode.taskId); put("scope_id", episode.scopeId); put("updated_at", episode.updatedAtEpochMs)
                                }, SQLiteDatabase.CONFLICT_REPLACE) }
                            }
                        }
                        else -> throw IOException("Unknown memory index operation; refusing to discard outbox entry.")
                    }
                }
                indexDb.setTransactionSuccessful()
            } finally { indexDb.endTransaction() }
            val last = changes.last().seq
            primary.beginTransaction()
            try {
                primary.delete("memory_index_outbox", "seq <= ?", arrayOf(last.toString()))
                primary.setTransactionSuccessful()
            } finally { primary.endTransaction() }
        }
    }

    private fun enqueueIndexChange(db: SQLiteDatabase, taskId: String, operation: String) {
        db.insertOrThrow("memory_index_outbox", null, ContentValues().apply { put("task_id", taskId); put("operation", operation) })
    }

    private fun flushAfterWrite() {
        runCatching { drainIndexOutbox() }
            .onSuccess { lastError = null }
            .onFailure {
                lastError = "Опыт сохранён, но синхронизация поискового индекса отложена до следующего обращения."
                recoveryNotice = lastError
            }
    }

    private fun markStorageFailure(operation: String) {
        val message = "Не удалось выполнить операцию с памятью ($operation). Проверь свободное место и доступность хранилища; старые записи автоматически не удаляются."
        lastError = message
        recoveryNotice = message
    }

    private fun writeMetadata(key: String, value: String) {
        val db = helper.writableDatabase
        db.insertWithOnConflict("memory_metadata", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun validateEpisode(item: SpiderLearningExperience) {
        require(item.taskId.isNotBlank() && item.taskId.length <= MAX_ID_CHARS && item.taskId.matches(ID_REGEX)) { "Learning task ID is invalid." }
        require(item.topicTokens.isNotEmpty() && item.topicTokens.size <= MAX_TOPIC_TOKENS && item.topicTokens.all(::isSafeToken)) { "Learning topic tokens are invalid." }
        require(item.errorCode == null || safeErrorCode(item.errorCode) == item.errorCode) { "Learning error code is invalid." }
        require(safeScope(item.scopeId) == item.scopeId) { "Learning scope ID is invalid." }
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
        return TOKEN_REGEX.findAll(scrubbed.lowercase(Locale.ROOT)).map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS && isSafeToken(it) }.distinct().take(MAX_TOPIC_TOKENS).toList()
    }

    private fun recencyWeight(updatedAtEpochMs: Long): Double {
        val ageDays = ((clock() - updatedAtEpochMs).coerceAtLeast(0L)) / MILLIS_PER_DAY
        return when { ageDays <= 7 -> 1.0; ageDays <= 30 -> 0.94; ageDays <= 90 -> 0.84; ageDays <= 365 -> 0.70; else -> 0.55 }
    }

    private fun confidenceFor(item: SpiderLearningExperience): Int = when (item.outcome) {
        SpiderLearningOutcome.VERIFIED_SUCCESS -> when (item.feedback) { SpiderLearningFeedback.HELPFUL -> 96; SpiderLearningFeedback.NOT_HELPFUL -> 12; null -> if (item.verified) 78 else 55 }
        SpiderLearningOutcome.FAILED -> if (item.feedback == SpiderLearningFeedback.NOT_HELPFUL) 25 else 42
        SpiderLearningOutcome.UNKNOWN -> when (item.feedback) { SpiderLearningFeedback.HELPFUL -> 62; SpiderLearningFeedback.NOT_HELPFUL -> 8; null -> 18 }
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
    private fun safeScope(scope: String): String = scope.takeIf { it == GLOBAL_SCOPE || it.matches(SCOPE_ID_REGEX) } ?: throw IllegalArgumentException("Learning scope ID is invalid.")
    private fun isSafeToken(token: String): Boolean = token.length in 3..MAX_TOKEN_CHARS && token.matches(Regex("[a-zа-яё_-]+")) && token !in SENSITIVE_TOKENS && token !in INSTRUCTION_LIKE_TOKENS
    private fun enumValueOutcome(value: String): SpiderLearningOutcome = enumValueOf(value)
    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf<T>(value)
    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    companion object {
        const val DATABASE_NAME = "spider-learning-v2.db"
        const val SEARCH_INDEX_DATABASE_NAME = "spider-search-index.db"
        private const val GLOBAL_SCOPE = "global"
        private val SCOPE_ID_REGEX = Regex("[A-Za-z0-9_.-]{1,120}")
        private val ID_REGEX = Regex("[A-Za-z0-9_.:-]{1,120}")
        private const val MAX_HINTS = 5
        private const val MAX_TOPIC_TOKENS = 12
        private const val MAX_TOOL_IDS = 5
        private const val MAX_ID_CHARS = 120
        private const val MAX_TOKEN_CHARS = 32
        private const val MAX_ERROR_CHARS = 80
        private const val MIN_SIMILARITY = 0.13
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val MAX_RETRIEVAL_CANDIDATES = 800
        private const val FALLBACK_CANDIDATES = 200
        private const val MAX_PAGE_SIZE = 500
        private const val SQL_BIND_CHUNK = 400
        private const val OUTBOX_BATCH_SIZE = 250
        private const val MIGRATION_BATCH_SIZE = 250
        private const val LEGACY_MIGRATION_KEY = "legacy_file_memory_import_v2"
        private const val MIGRATION_DONE = "complete"
        private const val OP_UPSERT = "UPSERT"
        private const val OP_DELETE = "DELETE"
        private const val OP_CLEAR = "CLEAR_ALL"
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
            "это", "как", "для", "при", "надо", "нужно", "чтобы", "мне", "меня", "тебя", "сделай", "пожалуйста", "можно", "будет", "есть", "или", "его", "она", "они", "оним", "все", "всё", "после", "перед", "через", "за", "задача", "задачу", "паук", "ии"
        )
        private val SENSITIVE_TOKENS = setOf("token", "secret", "password", "пароль", "токен", "ключ", "apikey", "bearer")
        private val INSTRUCTION_LIKE_TOKENS = setOf("ignore", "instructions", "system", "override", "execute", "shell", "jailbreak", "bypass", "игнорируй", "инструкции", "команда", "обойди")
        private val locks = ConcurrentHashMap<String, Any>()
    }
}

data class SpiderMemoryStorageStats(
    val episodeCount: Long,
    val topicTokenCount: Long,
    val pendingIndexChanges: Long,
    val memoryDatabaseBytes: Long,
    val searchIndexDatabaseBytes: Long
)

data class SpiderMemoryDiagnosticsSnapshot(
    val episodeCount: Long,
    val topicTokenCount: Long,
    val pendingIndexChanges: Long,
    val lexicalPostingCount: Long,
    val embeddingVectorCount: Long,
    val embeddingModelCount: Long,
    /** Main database plus SQLite WAL/SHM files; file names and paths are deliberately omitted. */
    val memoryDatabaseBytes: Long,
    val searchIndexDatabaseBytes: Long,
    val availableStorageBytes: Long,
    val totalStorageBytes: Long,
    val memorySchemaVersion: Int,
    val searchSchemaVersion: Int,
    /** Values are non-sensitive status tokens: ok, integrity_warning, or check_unavailable. */
    val memoryIntegrity: String,
    val searchIndexIntegrity: String,
    val lastStorageError: String?,
    val recoveryNotice: String?
)

internal class LearningMemoryDatabase(context: Context, private val databaseName: String) : SQLiteOpenHelper(context, databaseName, null, SCHEMA_VERSION) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE learning_experiences (task_id TEXT PRIMARY KEY NOT NULL, updated_at INTEGER NOT NULL, outcome TEXT NOT NULL, error_code TEXT, verified INTEGER NOT NULL, feedback TEXT, scope_id TEXT NOT NULL)")
        db.execSQL("CREATE INDEX learning_experiences_updated_idx ON learning_experiences(updated_at DESC, task_id DESC)")
        db.execSQL("CREATE INDEX learning_experiences_scope_idx ON learning_experiences(scope_id, outcome, updated_at DESC)")
        db.execSQL("CREATE TABLE learning_topic_tokens (task_id TEXT NOT NULL, ordinal INTEGER NOT NULL, token TEXT NOT NULL, PRIMARY KEY(task_id, ordinal), FOREIGN KEY(task_id) REFERENCES learning_experiences(task_id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX learning_topic_task_idx ON learning_topic_tokens(task_id)")
        db.execSQL("CREATE TABLE learning_tools (task_id TEXT NOT NULL, ordinal INTEGER NOT NULL, tool_id TEXT NOT NULL, PRIMARY KEY(task_id, ordinal), FOREIGN KEY(task_id) REFERENCES learning_experiences(task_id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE memory_index_outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, operation TEXT NOT NULL)")
        db.execSQL("CREATE TABLE memory_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned migrations are added explicitly. Never drop user memory during an upgrade.
        if (oldVersion < 1) onCreate(db)
    }

    fun databaseNameForDiagnostics(): String = databaseName

    companion object { private const val SCHEMA_VERSION = 1 }
}

private object LearningMemoryDatabaseRegistry {
    private val helpers = ConcurrentHashMap<String, LearningMemoryDatabase>()
    fun get(context: Context, name: String): LearningMemoryDatabase {
        val path = context.getDatabasePath(name).canonicalPath
        return helpers.computeIfAbsent(path) { LearningMemoryDatabase(context, name) }
    }
}

/** One shared, app-private SQLite database for token postings and optional dense vectors. */
internal class SpiderSearchIndexDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, VERSION) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE search_postings (term TEXT NOT NULL, task_id TEXT NOT NULL, scope_id TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(term, task_id))")
        db.execSQL("CREATE INDEX search_postings_term_idx ON search_postings(term, updated_at DESC)")
        db.execSQL("CREATE INDEX search_postings_scope_idx ON search_postings(scope_id, term)")
        db.execSQL("CREATE INDEX search_postings_task_idx ON search_postings(task_id)")
        db.execSQL("CREATE TABLE dense_embeddings (task_id TEXT NOT NULL, model_id TEXT NOT NULL, dimensions INTEGER NOT NULL, vector BLOB NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(task_id, model_id))")
        db.execSQL("CREATE INDEX dense_embeddings_model_idx ON dense_embeddings(model_id, task_id)")
        db.execSQL("CREATE TABLE search_index_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE embedding_backfill_state (model_id TEXT PRIMARY KEY NOT NULL, cursor_updated_at INTEGER NOT NULL, cursor_task_id TEXT NOT NULL)")
    }
    /** SQL inverted-index search; returned IDs are only candidates and must be re-ranked by memory logic. */
    fun candidateTaskIds(tokens: List<String>, scopeId: String?, limit: Int): List<String> {
        val terms = SpiderSemanticSearch.searchTerms(tokens).take(700).toList()
        if (terms.isEmpty() || limit <= 0) return emptyList()
        val ids = mutableListOf<String>()
        terms.chunked(700).forEach { termBatch ->
            val placeholders = List(termBatch.size) { "?" }.joinToString(",")
            val scopeClause = if (scopeId == null) "scope_id = 'global'" else "(scope_id = 'global' OR scope_id = ?)"
            val args = if (scopeId == null) termBatch.toTypedArray() else (termBatch + scopeId).toTypedArray()
            readableDatabase.rawQuery(
                "SELECT task_id, COUNT(*) AS hits, MAX(updated_at) AS updated_at FROM search_postings WHERE term IN ($placeholders) AND $scopeClause GROUP BY task_id ORDER BY hits DESC, updated_at DESC LIMIT ?",
                args + limit.coerceIn(1, 800).toString()
            ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
        }
        return ids.distinct().take(limit.coerceIn(1, 800))
    }

    fun putEmbeddings(embeddings: Map<String, SpiderDenseEmbedding>) {
        if (embeddings.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            embeddings.forEach { (taskId, embedding) ->
                val values = ContentValues().apply {
                    put("task_id", taskId); put("model_id", embedding.modelId); put("dimensions", embedding.values.size)
                    put("vector", SpiderVectorCodec.encode(embedding.values)); put("updated_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("dense_embeddings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun getEmbedding(taskId: String, modelId: String): SpiderDenseEmbedding? = readableDatabase.rawQuery(
        "SELECT model_id, dimensions, vector FROM dense_embeddings WHERE task_id = ? AND model_id = ?", arrayOf(taskId, modelId)
    ).use { cursor -> if (cursor.moveToFirst()) SpiderDenseEmbedding(cursor.getString(0), SpiderVectorCodec.decode(cursor.getBlob(2), cursor.getInt(1))) else null }

    fun hasEmbedding(taskId: String, modelId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM dense_embeddings WHERE task_id = ? AND model_id = ? LIMIT 1", arrayOf(taskId, modelId)
    ).use { it.moveToFirst() }

    fun getEmbeddings(taskIds: Set<String>, modelId: String): Map<String, SpiderDenseEmbedding> {
        if (taskIds.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, SpiderDenseEmbedding>()
        taskIds.toList().chunked(400).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            readableDatabase.rawQuery(
                "SELECT task_id, model_id, dimensions, vector FROM dense_embeddings WHERE model_id = ? AND task_id IN ($placeholders)",
                (listOf(modelId) + chunk).toTypedArray()
            ).use { cursor -> while (cursor.moveToNext()) result[cursor.getString(0)] = SpiderDenseEmbedding(cursor.getString(1), SpiderVectorCodec.decode(cursor.getBlob(3), cursor.getInt(2))) }
        }
        return result
    }

    fun snapshotEmbeddings(modelId: String): Map<String, SpiderDenseEmbedding> {
        val result = linkedMapOf<String, SpiderDenseEmbedding>()
        readableDatabase.rawQuery("SELECT task_id, model_id, dimensions, vector FROM dense_embeddings WHERE model_id = ? ORDER BY task_id", arrayOf(modelId)).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] = SpiderDenseEmbedding(cursor.getString(1), SpiderVectorCodec.decode(cursor.getBlob(3), cursor.getInt(2)))
        }
        return result
    }

    /** Streams the vector table and retains only the best K embeddings in memory. */
    fun nearestEmbeddings(query: SpiderDenseEmbedding, limit: Int): Map<String, SpiderDenseEmbedding> {
        val bounded = limit.coerceIn(1, 2_000)
        val best = java.util.PriorityQueue<Pair<String, SpiderDenseEmbedding>>(compareBy { cosineSimilarity(query, it.second) ?: -1.0 })
        readableDatabase.rawQuery("SELECT task_id, model_id, dimensions, vector FROM dense_embeddings WHERE model_id = ?", arrayOf(query.modelId)).use { cursor ->
            while (cursor.moveToNext()) {
                val item = SpiderDenseEmbedding(cursor.getString(1), SpiderVectorCodec.decode(cursor.getBlob(3), cursor.getInt(2)))
                val score = cosineSimilarity(query, item) ?: continue
                if (best.size < bounded) best.add(cursor.getString(0) to item)
                else if (score > (cosineSimilarity(query, best.peek().second) ?: -1.0)) { best.poll(); best.add(cursor.getString(0) to item) }
            }
        }
        return best.sortedByDescending { cosineSimilarity(query, it.second) ?: -1.0 }.associate { it.first to it.second }
    }

    fun removeEmbeddings(taskId: String): Boolean = writableDatabase.delete("dense_embeddings", "task_id = ?", arrayOf(taskId)) > 0
    fun clearEmbeddings() = writableDatabase.delete("dense_embeddings", null, null)
    fun embeddingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM dense_embeddings", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    data class BackfillCursor(val updatedAtEpochMs: Long, val taskId: String)
    fun backfillCursor(modelId: String): BackfillCursor? = readableDatabase.rawQuery(
        "SELECT cursor_updated_at, cursor_task_id FROM embedding_backfill_state WHERE model_id = ?", arrayOf(modelId)
    ).use { if (it.moveToFirst()) BackfillCursor(it.getLong(0), it.getString(1)) else null }
    fun setBackfillCursor(modelId: String, cursor: BackfillCursor?) {
        val db = writableDatabase
        if (cursor == null) db.delete("embedding_backfill_state", "model_id = ?", arrayOf(modelId))
        else db.insertWithOnConflict("embedding_backfill_state", null, ContentValues().apply { put("model_id", modelId); put("cursor_updated_at", cursor.updatedAtEpochMs); put("cursor_task_id", cursor.taskId) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }
    companion object { private const val VERSION = 1 }
}

internal object SpiderVectorCodec {
    fun encode(values: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(values.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }
    fun decode(bytes: ByteArray, dimensions: Int): FloatArray {
        require(dimensions in 8..4096 && bytes.size == dimensions * 4) { "Stored vector dimensions are invalid." }
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimensions) { buffer.float.also { value -> require(value.isFinite()) { "Stored vector contains a non-finite value." } } }
    }
}

internal object SpiderSearchIndexRegistry {
    private val helpers = ConcurrentHashMap<String, SpiderSearchIndexDatabase>()
    fun get(context: Context, name: String): SpiderSearchIndexDatabase {
        val path = context.getDatabasePath(name).canonicalPath
        return helpers.computeIfAbsent(path) { SpiderSearchIndexDatabase(context, name) }
    }
}

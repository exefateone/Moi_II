package com.iipauk.spider

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * SQLite-backed search-vector store. This database is separate from episodic memory so the token
 * inverted index and optional dense vectors can be rebuilt or tuned without rewriting episodes.
 * There is no fixed application-level byte ceiling; queries stream rows and keep only bounded top-K.
 */
class SqliteSpiderEmbeddingIndex(
    context: Context,
    private val legacyStoreFile: File? = null,
    databaseName: String = SqliteSpiderLearningMemory.SEARCH_INDEX_DATABASE_NAME
) {
    private val appContext = context.applicationContext
    private val helper = SpiderSearchIndexRegistry.get(appContext, databaseName)
    private val lock = locks.computeIfAbsent(appContext.getDatabasePath(databaseName).canonicalPath) { Any() }

    @Volatile var migrationNotice: String? = null
        private set

    init { synchronized(lock) { runCatching { migrateLegacyIfNeeded() }.onFailure {
        migrationNotice = "Старый файл embeddings сохранён; перенос в SQLite можно повторить."
    } } }

    fun put(taskId: String, embedding: SpiderDenseEmbedding) = putAll(mapOf(taskId to embedding))

    fun putAll(embeddings: Map<String, SpiderDenseEmbedding>) = synchronized(lock) {
        if (embeddings.isEmpty()) return@synchronized
        val normalized = linkedMapOf<String, SpiderDenseEmbedding>()
        embeddings.forEach { (taskId, embedding) ->
            require(taskId.matches(TASK_ID_REGEX)) { "Embedding task ID is invalid." }
            normalized[taskId] = SpiderDenseEmbedding(embedding.modelId, normalizeAndValidate(embedding))
        }
        helper.putEmbeddings(normalized)
    }

    fun get(taskId: String, modelId: String): SpiderDenseEmbedding? = synchronized(lock) {
        if (!taskId.matches(TASK_ID_REGEX) || !modelId.matches(MODEL_ID_REGEX)) return@synchronized null
        helper.getEmbedding(taskId, modelId)?.let { SpiderDenseEmbedding(it.modelId, it.values.copyOf()) }
    }

    fun getMany(taskIds: Set<String>, modelId: String): Map<String, SpiderDenseEmbedding> = synchronized(lock) {
        require(modelId.matches(MODEL_ID_REGEX)) { "Embedding model ID is invalid." }
        val safeIds = taskIds.filter { it.matches(TASK_ID_REGEX) }.toSet()
        helper.getEmbeddings(safeIds, modelId).mapValues { (_, value) -> SpiderDenseEmbedding(value.modelId, value.values.copyOf()) }
    }

    fun has(taskId: String, modelId: String): Boolean = synchronized(lock) {
        taskId.matches(TASK_ID_REGEX) && modelId.matches(MODEL_ID_REGEX) && helper.hasEmbedding(taskId, modelId)
    }

    /** Compatibility API. Prefer nearest() / getMany() for large indexes to bound heap usage. */
    fun snapshot(modelId: String): Map<String, SpiderDenseEmbedding> = synchronized(lock) {
        require(modelId.matches(MODEL_ID_REGEX)) { "Embedding model ID is invalid." }
        helper.snapshotEmbeddings(modelId).mapValues { (_, value) -> SpiderDenseEmbedding(value.modelId, value.values.copyOf()) }
    }

    fun nearest(query: SpiderDenseEmbedding, limit: Int = 256): Map<String, SpiderDenseEmbedding> = synchronized(lock) {
        val normalized = normalizeAndValidate(query)
        helper.nearestEmbeddings(SpiderDenseEmbedding(query.modelId, normalized), limit)
            .mapValues { (_, value) -> SpiderDenseEmbedding(value.modelId, value.values.copyOf()) }
    }

    fun remove(taskId: String): Boolean = synchronized(lock) { helper.removeEmbeddings(taskId) }

    /** Removes only dense vectors; lexical postings remain owned by episodic-memory outbox. */
    fun clearAll() = synchronized(lock) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("dense_embeddings", null, null)
            db.delete("embedding_backfill_state", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun count(): Int = synchronized(lock) { helper.embeddingCount() }

    data class BackfillCursor(val updatedAtEpochMs: Long, val taskId: String)
    fun backfillCursor(modelId: String): BackfillCursor? = synchronized(lock) {
        helper.backfillCursor(modelId)?.let { BackfillCursor(it.updatedAtEpochMs, it.taskId) }
    }
    fun setBackfillCursor(modelId: String, cursor: BackfillCursor?) = synchronized(lock) {
        helper.setBackfillCursor(modelId, cursor?.let { SpiderSearchIndexDatabase.BackfillCursor(it.updatedAtEpochMs, it.taskId) })
    }

    private fun migrateLegacyIfNeeded() {
        val source = legacyStoreFile ?: return
        val db = helper.writableDatabase
        val status = db.rawQuery("SELECT value FROM search_index_metadata WHERE key = ?", arrayOf(LEGACY_MIGRATION_KEY)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (status == MIGRATION_DONE) return
        if (!source.exists()) {
            db.insertWithOnConflict("search_index_metadata", null, ContentValues().apply { put("key", LEGACY_MIGRATION_KEY); put("value", MIGRATION_DONE) }, SQLiteDatabase.CONFLICT_REPLACE)
            return
        }
        FileSpiderEmbeddingIndex(source).forEachMigrationBatch(MIGRATION_BATCH_SIZE) { batch -> putAll(batch) }
        db.insertWithOnConflict("search_index_metadata", null, ContentValues().apply { put("key", LEGACY_MIGRATION_KEY); put("value", MIGRATION_DONE) }, SQLiteDatabase.CONFLICT_REPLACE)
        val archive = File(source.parentFile, source.name + ".migrated-v2")
        if (!archive.exists() && !source.renameTo(archive)) migrationNotice = "Embeddings перенесены, но прежний файл не удалось переименовать; он сохранён."
        else migrationNotice = "Embeddings перенесены в отдельный SQLite-индекс."
    }

    private fun normalizeAndValidate(embedding: SpiderDenseEmbedding): FloatArray {
        require(embedding.modelId.matches(MODEL_ID_REGEX)) { "Embedding model ID is invalid." }
        require(embedding.values.size in MIN_DIMENSIONS..MAX_DIMENSIONS) { "Embedding dimensions are invalid." }
        require(embedding.values.all { it.isFinite() }) { "Embedding contains a non-finite value." }
        val values = embedding.values.copyOf()
        val norm = sqrt(values.sumOf { it.toDouble() * it.toDouble() })
        require(norm > 0.0) { "Embedding vector must not be zero." }
        for (index in values.indices) values[index] = (values[index] / norm).toFloat()
        return values
    }

    companion object {
        private const val MIN_DIMENSIONS = 8
        private const val MAX_DIMENSIONS = 4096
        private const val MIGRATION_BATCH_SIZE = 128
        private const val LEGACY_MIGRATION_KEY = "legacy_embeddings_file_import_v2"
        private const val MIGRATION_DONE = "complete"
        private val TASK_ID_REGEX = Regex("[A-Za-z0-9_.:-]{1,120}")
        private val MODEL_ID_REGEX = Regex("[A-Za-z0-9_.:/-]{1,160}")
        private val locks = ConcurrentHashMap<String, Any>()
    }
}

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
import kotlin.math.sqrt

/** An embedding is only usable with vectors produced by the same configured model ID. */
data class SpiderDenseEmbedding(val modelId: String, val values: FloatArray)

/**
 * Legacy single-file vector index kept for import and compatibility tests. Production runtime uses
 * SqliteSpiderEmbeddingIndex; the byte guard below applies only to this legacy format. It stores
 * normalized vectors keyed by task ID; sanitized topic tokens are held separately in memory.
 */
class FileSpiderEmbeddingIndex(private val storeFile: File) {
    private val canonicalPath = storeFile.canonicalFile.absolutePath
    private val lock = locks.computeIfAbsent(canonicalPath) { Any() }

    fun put(taskId: String, embedding: SpiderDenseEmbedding) = synchronized(lock) {
        require(taskId.matches(TASK_ID_REGEX)) { "Embedding task ID is invalid." }
        val normalized = normalizeAndValidate(embedding)
        val all = readLocked()
        all[taskId] = SpiderDenseEmbedding(embedding.modelId, normalized)
        writeLocked(all)
    }

    /** Atomically indexes a backfill batch with one store rewrite, avoiding repeated full-file writes. */
    fun putAll(embeddings: Map<String, SpiderDenseEmbedding>) = synchronized(lock) {
        if (embeddings.isEmpty()) return@synchronized
        val normalized = linkedMapOf<String, SpiderDenseEmbedding>()
        embeddings.forEach { (taskId, embedding) ->
            require(taskId.matches(TASK_ID_REGEX)) { "Embedding task ID is invalid." }
            normalized[taskId] = SpiderDenseEmbedding(embedding.modelId, normalizeAndValidate(embedding))
        }
        val all = readLocked()
        all.putAll(normalized)
        writeLocked(all)
    }

    /**
     * Streams the legacy binary embedding store for one-time import without materializing every
     * vector at once. The old file remains untouched until the importer confirms completion.
     */
    fun forEachMigrationBatch(batchSize: Int = 128, consume: (Map<String, SpiderDenseEmbedding>) -> Unit) = synchronized(lock) {
        require(batchSize in 1..1_000) { "Migration batch size is outside the safe range." }
        if (!storeFile.exists()) return@synchronized
        if (storeFile.length() <= 0L || storeFile.length() > MAX_LEGACY_STORE_BYTES) {
            throw IOException("Legacy embedding index size is invalid; original file was preserved.")
        }
        DataInputStream(BufferedInputStream(FileInputStream(storeFile))).use { input ->
            if (input.readInt() != MAGIC) throw IOException("Legacy embedding index signature is invalid; original preserved.")
            if (input.readInt() != SCHEMA_VERSION) throw IOException("Legacy embedding index schema is unsupported; original preserved.")
            val count = input.readInt()
            if (count < 0 || count.toLong() > storeFile.length() / 32L + 1L) throw IOException("Legacy embedding count is invalid; original preserved.")
            val batch = linkedMapOf<String, SpiderDenseEmbedding>()
            repeat(count) {
                val taskId = readString(input, MAX_TASK_ID_CHARS)
                val modelId = readString(input, MAX_MODEL_ID_CHARS)
                val dimensions = input.readInt()
                if (dimensions !in MIN_DIMENSIONS..MAX_DIMENSIONS) throw IOException("Legacy embedding dimensions are invalid; original preserved.")
                val values = FloatArray(dimensions) { input.readFloat() }
                if (values.any { !it.isFinite() }) throw IOException("Legacy embedding contains a non-finite value; original preserved.")
                if (!taskId.matches(TASK_ID_REGEX) || modelId.isBlank()) throw IOException("Legacy embedding identifiers are invalid; original preserved.")
                batch[taskId] = SpiderDenseEmbedding(modelId, normalizeAndValidate(SpiderDenseEmbedding(modelId, values)))
                if (batch.size >= batchSize) { consume(batch.toMap()); batch.clear() }
            }
            if (input.read() != -1) throw IOException("Legacy embedding index contains trailing data; original preserved.")
            if (batch.isNotEmpty()) consume(batch.toMap())
        }
    }

    fun get(taskId: String, modelId: String): SpiderDenseEmbedding? = synchronized(lock) {
        readLocked()[taskId]?.takeIf { it.modelId == modelId }?.let { SpiderDenseEmbedding(it.modelId, it.values.copyOf()) }
    }

    fun has(taskId: String, modelId: String): Boolean = synchronized(lock) {
        readLocked()[taskId]?.let { it.modelId == modelId } == true
    }

    /** One read per retrieval avoids reopening the full vector file for every episode. */
    fun snapshot(modelId: String): Map<String, SpiderDenseEmbedding> = synchronized(lock) {
        readLocked().filterValues { it.modelId == modelId }.mapValues { (_, item) -> SpiderDenseEmbedding(item.modelId, item.values.copyOf()) }
    }

    fun remove(taskId: String): Boolean = synchronized(lock) {
        val all = readLocked()
        if (all.remove(taskId) == null) return@synchronized false
        writeLocked(all)
        true
    }

    fun clearAll() = synchronized(lock) {
        if (storeFile.exists() && !storeFile.delete()) throw IOException("Не удалось удалить семантический индекс.")
        File(storeFile.parentFile, storeFile.name + ".tmp").delete()
    }

    fun count(): Int = synchronized(lock) { readLocked().size }

    private fun readLocked(): LinkedHashMap<String, SpiderDenseEmbedding> {
        if (!storeFile.exists()) return linkedMapOf()
        if (storeFile.length() <= 0L || storeFile.length() > MAX_LEGACY_STORE_BYTES) {
            throw IOException("Семантический индекс имеет некорректный или слишком большой размер; исходные данные сохранены.")
        }
        try {
            DataInputStream(BufferedInputStream(FileInputStream(storeFile))).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Semantic index signature is invalid.")
                val version = input.readInt()
                if (version != SCHEMA_VERSION) throw IOException("Unsupported semantic index schema; data preserved.")
                val count = input.readInt()
                if (count < 0 || count.toLong() > storeFile.length() / 32L + 1L) throw IOException("Semantic index count is invalid.")
                val all = linkedMapOf<String, SpiderDenseEmbedding>()
                repeat(count) {
                    val taskId = readString(input, MAX_TASK_ID_CHARS)
                    val modelId = readString(input, MAX_MODEL_ID_CHARS)
                    val dimensions = input.readInt()
                    if (dimensions !in MIN_DIMENSIONS..MAX_DIMENSIONS) throw IOException("Embedding dimensions are invalid.")
                    val values = FloatArray(dimensions) { input.readFloat() }
                    if (values.any { !it.isFinite() }) throw IOException("Embedding contains a non-finite value.")
                    if (all.put(taskId, SpiderDenseEmbedding(modelId, values)) != null) throw IOException("Duplicate embedding task ID.")
                }
                if (input.read() != -1) throw IOException("Unexpected trailing bytes in semantic index.")
                return all
            }
        } catch (eof: EOFException) {
            throw IOException("Semantic index is truncated; original file is preserved.", eof)
        }
    }

    private fun writeLocked(items: Map<String, SpiderDenseEmbedding>) {
        val parent = storeFile.absoluteFile.parentFile ?: throw IOException("Semantic index must be app-private.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create semantic index directory.")
        val temp = File(parent, storeFile.name + ".tmp")
        try {
            val fs = FileOutputStream(temp, false)
            DataOutputStream(BufferedOutputStream(fs)).use { out ->
                out.writeInt(MAGIC); out.writeInt(SCHEMA_VERSION); out.writeInt(items.size)
                items.forEach { (taskId, embedding) ->
                    val values = normalizeAndValidate(embedding)
                    writeString(out, taskId, MAX_TASK_ID_CHARS)
                    writeString(out, embedding.modelId, MAX_MODEL_ID_CHARS)
                    out.writeInt(values.size); values.forEach(out::writeFloat)
                }
                out.flush(); fs.fd.sync()
            }
            if (temp.length() > MAX_LEGACY_STORE_BYTES) throw IOException("Старый файловый индекс embeddings достиг предела миграционного формата; существующие векторы сохранены.")
            try {
                Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            temp.delete(); throw failure
        }
    }

    private fun normalizeAndValidate(embedding: SpiderDenseEmbedding): FloatArray {
        require(embedding.modelId.length in 1..MAX_MODEL_ID_CHARS && embedding.modelId.matches(MODEL_ID_REGEX)) { "Embedding model ID is invalid." }
        require(embedding.values.size in MIN_DIMENSIONS..MAX_DIMENSIONS) { "Embedding dimensions are invalid." }
        require(embedding.values.all { it.isFinite() }) { "Embedding has non-finite values." }
        val norm = sqrt(embedding.values.sumOf { it.toDouble() * it.toDouble() })
        require(norm > 1e-12 && norm.isFinite()) { "Embedding norm is zero or invalid." }
        return FloatArray(embedding.values.size) { i -> (embedding.values[i] / norm).toFloat() }
    }

    private fun writeString(out: DataOutputStream, value: String, max: Int) {
        require(value.length <= max)
        val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= max * 4)
        out.writeInt(bytes.size); out.write(bytes)
    }
    private fun readString(input: DataInputStream, max: Int): String {
        val length = input.readInt(); if (length !in 0..max * 4) throw IOException("Semantic index field length is invalid.")
        val bytes = ByteArray(length); input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8).also { if (it.length > max) throw IOException("Semantic index field exceeds limit.") }
    }

    companion object {
        private const val MAGIC = 0x53504549 // SPEI
        private const val SCHEMA_VERSION = 1
        private const val MAX_LEGACY_STORE_BYTES = 64L * 1024L * 1024L
        private const val MAX_TASK_ID_CHARS = 120
        private const val MAX_MODEL_ID_CHARS = 120
        private const val MIN_DIMENSIONS = 8
        private const val MAX_DIMENSIONS = 2048
        private val TASK_ID_REGEX = Regex("[A-Za-z0-9._:-]{1,120}")
        private val MODEL_ID_REGEX = Regex("[A-Za-z0-9_.:/@-]{1,120}")
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}

/** Cosine similarity is valid only for vectors from the same embedding model and dimension. */
fun cosineSimilarity(left: SpiderDenseEmbedding, right: SpiderDenseEmbedding): Double? {
    if (left.modelId != right.modelId || left.values.size != right.values.size || left.values.isEmpty()) return null
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    for (index in left.values.indices) {
        val a = left.values[index].toDouble()
        val b = right.values[index].toDouble()
        if (!a.isFinite() || !b.isFinite()) return null
        dot += a * b; leftNorm += a * a; rightNorm += b * b
    }
    if (leftNorm <= 1e-12 || rightNorm <= 1e-12) return null
    return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).coerceIn(-1.0, 1.0)
}

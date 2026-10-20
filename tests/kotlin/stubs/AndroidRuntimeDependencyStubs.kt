package com.iipauk.spider

import android.content.Context
import java.io.File

/** Signature-compatible doubles used only to type-check SpiderAgentRuntime outside Android Gradle. */
class WorkspaceRepository(context: Context) {
    data class Workspace(val id: String, val name: String, val directory: File, val createdAt: Long)
    fun directoryFor(id: String): File? = null
    fun list(): List<Workspace> = emptyList()
    fun create(rawName: String): Workspace = throw UnsupportedOperationException("Workspace creation is outside core smoke")
}


/** Compile-time substitute for Android SQLite task history in pure JVM smoke tests only. */
class SqliteSpiderTaskRepository(context: Context, legacyStoreFile: File, databaseName: String = DATABASE_NAME) : SpiderTaskRepository {
    private val delegateStore = FileSpiderTaskRepository(legacyStoreFile)
    val recoveryNotice: String? get() = delegateStore.recoveryNotice
    val lastStorageError: String? = null
    override fun find(taskId: String): SpiderAgentTask? = delegateStore.find(taskId)
    override fun save(task: SpiderAgentTask) = delegateStore.save(task)
    override fun all(): List<SpiderAgentTask> = delegateStore.all()
    companion object { const val DATABASE_NAME = "spider-task-history-v1.db" }
}

/** Compile-time substitute for Android Keystore configuration in pure JVM core tests only. */
class SpiderProviderConfigStore(context: Context) {
    fun save(endpointUrl: String, clientToken: String) = Unit
    fun clear() = Unit
    fun load(): SpiderProviderConnection? = null
}

/** Compile-time stand-in for Android Keystore-backed OpenCode Zen settings in core smoke only. */
class SpiderOpenCodeZenConfigStore(context: Context) {
    fun save(config: SpiderOpenCodeZenConfig) = Unit
    fun clear() = Unit
    fun load(): SpiderOpenCodeZenConfig? = null
    fun isConfigured(): Boolean = false
}

/** Runtime stand-in for network-only OpenCode Zen classes in pure Agent Core smoke tests. */
class OpenCodeZenSpiderTaskPlanner(
    config: SpiderOpenCodeZenConfig,
    client: Any? = null,
    learningContextProvider: (SpiderAgentTask) -> List<SpiderLearningHint> = { emptyList() }
) : SpiderTaskPlanner {
    override fun proposePlan(task: SpiderAgentTask): List<SpiderPlanStep> = throw UnsupportedOperationException("Network planner is not invoked in core smoke")
}


/** Compile-time/runtime double for pure Kotlin smoke only; device tests exercise the real SQLite implementation. */
class SqliteSpiderLearningMemory(context: Context, legacyStoreFile: File) {
    private val delegate = FileSpiderLearningMemory(legacyStoreFile)
    fun observeTerminalTask(task: SpiderAgentTask) = delegate.observeTerminalTask(task)
    fun recordFeedback(task: SpiderAgentTask, helpful: Boolean): Boolean = delegate.recordFeedback(task, helpful)
    fun recordFeedback(taskId: String, helpful: Boolean): Boolean = delegate.recordFeedback(taskId, helpful)
    fun relevantHints(goal: String, limit: Int = 5, scopeId: String? = null, queryEmbedding: SpiderDenseEmbedding? = null,
        semanticIndex: Map<String, SpiderDenseEmbedding> = emptyMap(), additionalCandidateTaskIds: Set<String> = emptySet()): List<SpiderLearningHint> =
        delegate.relevantHints(goal, limit, scopeId, queryEmbedding, semanticIndex)
    fun candidateTaskIds(goal: String, scopeId: String? = null, limit: Int = 800): List<String> = emptyList()
    fun listExperiences(limit: Int = 50): List<SpiderLearningExperience> = delegate.listExperiences(limit)
    fun listExperiencesPage(offset: Int, limit: Int = 100): List<SpiderLearningExperience> = delegate.listExperiences(limit + offset).drop(offset)
    fun listExperiencesAfter(updatedAtEpochMs: Long, taskId: String, limit: Int = 100): List<SpiderLearningExperience> =
        delegate.listExperiences(delegate.count()).filter { it.updatedAtEpochMs > updatedAtEpochMs || (it.updatedAtEpochMs == updatedAtEpochMs && it.taskId > taskId) }.take(limit)
    fun count(): Int = delegate.count()
    fun safeTopicText(rawGoal: String): String = delegate.safeTopicText(rawGoal)
    fun forget(taskId: String): Boolean = delegate.forget(taskId)
    fun clearAll() = delegate.clearAll()
}

/** Compile-time/runtime double for pure Kotlin smoke only; Android instrumentation tests verify SQLite. */
class SqliteSpiderEmbeddingIndex(context: Context, legacyStoreFile: File? = null) {
    private val delegate = FileSpiderEmbeddingIndex(legacyStoreFile ?: File(context.filesDir, "stub-embedding-index.spider"))
    data class BackfillCursor(val updatedAtEpochMs: Long, val taskId: String)
    fun put(taskId: String, embedding: SpiderDenseEmbedding) = delegate.put(taskId, embedding)
    fun putAll(embeddings: Map<String, SpiderDenseEmbedding>) = delegate.putAll(embeddings)
    fun get(taskId: String, modelId: String): SpiderDenseEmbedding? = delegate.get(taskId, modelId)
    fun getMany(taskIds: Set<String>, modelId: String): Map<String, SpiderDenseEmbedding> = delegate.snapshot(modelId).filterKeys { it in taskIds }
    fun has(taskId: String, modelId: String): Boolean = delegate.has(taskId, modelId)
    fun snapshot(modelId: String): Map<String, SpiderDenseEmbedding> = delegate.snapshot(modelId)
    fun nearest(query: SpiderDenseEmbedding, limit: Int = 256): Map<String, SpiderDenseEmbedding> = delegate.snapshot(query.modelId).entries.sortedByDescending { cosineSimilarity(query, it.value) ?: -1.0 }.take(limit).associate { it.toPair() }
    fun remove(taskId: String): Boolean = delegate.remove(taskId)
    fun clearAll() = delegate.clearAll()
    fun count(): Int = delegate.count()
    fun backfillCursor(modelId: String): BackfillCursor? = null
    fun setBackfillCursor(modelId: String, cursor: BackfillCursor?) = Unit
}


/** Test-only stand-in for the native overlay service referenced by runtime capability inspection. */
object SpiderOverlayService {
    fun isEnabled(context: Context): Boolean = false
}

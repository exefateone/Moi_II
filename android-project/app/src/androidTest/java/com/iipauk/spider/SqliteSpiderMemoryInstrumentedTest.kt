package com.iipauk.spider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqliteSpiderMemoryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString().replace("-", "")
    private val memoryDb = "spider-learning-test-$suffix.db"
    private val searchDb = "spider-search-test-$suffix.db"

    @Test
    fun importsLegacyMemoryStreamsPastFiveHundredAndIndexesIndependently() {
        val legacy = File(context.cacheDir, "legacy-learning-$suffix.spider")
        // Build a valid legacy v2 fixture in one write so this migration test does not benchmark
        // the old whole-file rewrite implementation (which fsyncs the entire archive per task).
        DataOutputStream(BufferedOutputStream(FileOutputStream(legacy))).use { out ->
            out.writeInt(0x53504C4D) // SPLM legacy signature
            out.writeInt(2)          // legacy schema with workspace scope
            out.writeInt(540)
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
            fun optional(value: String?) { out.writeBoolean(value != null); if (value != null) text(value) }
            repeat(540) { index ->
                text("task_$index")
                out.writeLong(1_000L + index)
                val tokens = if (index == 17) listOf("inspect", "workspace", "files", "raremarker") else listOf("inspect", "workspace", "files", "commonresult")
                out.writeInt(tokens.size); tokens.forEach(::text)
                text("VERIFIED_SUCCESS")
                optional(null) // error code
                out.writeInt(0) // tool IDs
                out.writeBoolean(true)
                optional(null) // user feedback
                text("workspace_a")
            }
            out.flush()
        }

        val memory = SqliteSpiderLearningMemory(context, legacy, memoryDb, searchDb, clock = { 10_000L })
        assertEquals("all legacy episodes should survive import without a 500-item cap", 540, memory.count())
        assertNotNull("SQLite search index should be built during migration", memory.candidateTaskIds("find raremarker in workspace files", "workspace_a").firstOrNull())
        assertEquals(100, memory.listExperiencesPage(0, 100).size)
        assertEquals(125, memory.listExperiencesAfter(Long.MIN_VALUE, "", 125).size)
        assertTrue("legacy source remains available as a migration archive", File(legacy.parentFile, legacy.name + ".migrated-v2").exists())

        val rare = memory.candidateTaskIds("inspect raremarker workspace", "workspace_a")
        assertTrue("inverted index finds the matching episode", "task_17" in rare)
        assertTrue(memory.forget("task_17"))
        assertFalse("forget removes posting from separate index", "task_17" in memory.candidateTaskIds("inspect raremarker workspace", "workspace_a"))
        assertEquals(539, memory.count())

        memory.clearAll()
        assertEquals(0, memory.count())
        assertTrue(memory.candidateTaskIds("inspect workspace files", "workspace_a").isEmpty())
    }

    @Test
    fun denseVectorIndexStoresAndSearchesWithoutLoadingWholeIndex() {
        val index = SqliteSpiderEmbeddingIndex(context, null, searchDb)
        val modelId = "offline-test-model"
        val vectors = (0 until 1_100).associate { i ->
            "vector_$i" to SpiderDenseEmbedding(modelId, FloatArray(8) { slot -> if (slot == i % 8) 1f else 0.01f })
        }
        index.putAll(vectors)
        assertEquals(1_100, index.count())
        assertTrue(index.has("vector_10", modelId))
        assertEquals(1, index.getMany(setOf("vector_10", "missing_vector"), modelId).size)
        assertTrue("top-K retrieval stays bounded", index.nearest(SpiderDenseEmbedding(modelId, FloatArray(8) { if (it == 2) 1f else 0f }), 7).size <= 7)
        assertNotNull(index.get("vector_10", modelId))
    }
    @Test
    fun diagnosticsReportCountsSizesAndReadOnlyIntegrityWithoutPaths() {
        val legacy = File(context.cacheDir, "diagnostics-legacy-$suffix.spider")
        val memory = SqliteSpiderLearningMemory(context, legacy, memoryDb, searchDb, clock = { 20_000L })
        val task = SpiderAgentTask(
            id = "diagnostic_task_1",
            goal = "inspect workspace files",
            status = SpiderTaskStatus.COMPLETED,
            steps = listOf(SpiderPlanStep("step1", "list workspace", SpiderToolId.LIST_WORKSPACE_FILES, SpiderStepStatus.SUCCEEDED)),
            createdAtEpochMs = 19_000L,
            updatedAtEpochMs = 20_000L,
            workspaceScopeId = "workspace_diag"
        )
        memory.observeTerminalTask(task)
        val index = SqliteSpiderEmbeddingIndex(context, null, searchDb)
        index.put("diagnostic_task_1", SpiderDenseEmbedding("diagnostic-model", FloatArray(8) { slot -> if (slot == 0) 1f else 0.01f }))

        val snapshot = memory.diagnosticsSnapshot()
        assertEquals(1L, snapshot.episodeCount)
        assertTrue("topics should be counted without reading private strings into diagnostics", snapshot.topicTokenCount > 0)
        assertTrue(snapshot.lexicalPostingCount > 0)
        assertEquals(1L, snapshot.embeddingVectorCount)
        assertEquals(1L, snapshot.embeddingModelCount)
        assertTrue(snapshot.memoryDatabaseBytes > 0L)
        assertTrue(snapshot.searchIndexDatabaseBytes > 0L)
        assertTrue(snapshot.availableStorageBytes >= 0L)
        assertTrue(snapshot.totalStorageBytes >= snapshot.availableStorageBytes)
        assertEquals("ok", snapshot.memoryIntegrity)
        assertEquals("ok", snapshot.searchIndexIntegrity)
        assertFalse("diagnostic snapshot must not expose file paths", snapshot.toString().contains(context.filesDir.absolutePath))
        assertFalse("diagnostic snapshot must not contain task content", snapshot.toString().contains(task.goal))
    }

}

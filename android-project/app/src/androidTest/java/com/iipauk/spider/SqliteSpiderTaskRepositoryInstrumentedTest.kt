package com.iipauk.spider

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqliteSpiderTaskRepositoryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString().replace("-", "")
    private val dbName = "spider-task-history-test-$suffix.db"

    @Test
    fun importsLegacyHistoryAtomicallyAndKeepsArchiveAfterRestart() {
        val legacy = File(context.cacheDir, "legacy-task-history-$suffix.spider")
        val expected = listOf(
            SpiderAgentTask(
                id = "legacy-task-a", goal = "Проверить рабочую область", status = SpiderTaskStatus.FAILED,
                steps = listOf(SpiderPlanStep("step-a", "Список файлов", SpiderToolId.LIST_WORKSPACE_FILES, SpiderStepStatus.FAILED, "Прервано")),
                createdAtEpochMs = 1_000L, updatedAtEpochMs = 1_200L,
                lastErrorCode = "execution_interrupted_unknown_outcome", resultSummary = "Проверь результат отдельно",
                workspaceScopeId = "workspace_alpha"
            ),
            SpiderAgentTask(
                id = "legacy-task-b", goal = "Подготовить HTML", status = SpiderTaskStatus.COMPLETED,
                steps = listOf(SpiderPlanStep("step-b", "Список файлов", SpiderToolId.LIST_WORKSPACE_FILES, SpiderStepStatus.SUCCEEDED, "3 элемента")),
                createdAtEpochMs = 2_000L, updatedAtEpochMs = 2_400L,
                resultSummary = "Инвентаризация проверена", workspaceScopeId = null
            )
        )
        writeLegacyV2(legacy, expected)
        try {
            val repo = SqliteSpiderTaskRepository(context, legacy, dbName)
            assertEquals(expected.size, repo.count())
            assertEquals(expected.associateBy { it.id }, repo.all().associateBy { it.id })
            assertEquals(expected.first(), repo.find("legacy-task-a"))
            assertEquals(listOf("legacy-task-b", "legacy-task-a"), repo.recent(2).map { it.id })

            val archive = File(legacy.parentFile, legacy.name + ".migrated-sqlite-v1")
            assertTrue("legacy data must remain available as a migration archive", archive.exists())
            assertFalse("original path should be moved only after successful verification", legacy.exists())

            val reopened = SqliteSpiderTaskRepository(context, legacy, dbName)
            assertEquals(expected.associateBy { it.id }, reopened.all().associateBy { it.id })
            assertTrue(archive.exists())
        } finally {
            legacy.delete()
            File(legacy.parentFile, legacy.name + ".migrated-sqlite-v1").delete()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun recoversPendingImportAfterSimulatedProcessDeath() {
        val legacy = File(context.cacheDir, "pending-task-history-$suffix.spider")
        val expected = listOf(
            SpiderAgentTask(
                id = "pending-task", goal = "Восстановить перенос", status = SpiderTaskStatus.FAILED,
                steps = listOf(SpiderPlanStep("pending-step", "Список файлов", SpiderToolId.LIST_WORKSPACE_FILES, SpiderStepStatus.FAILED, "Итог не подтверждён")),
                createdAtEpochMs = 10L, updatedAtEpochMs = 20L,
                lastErrorCode = "execution_interrupted_unknown_outcome", resultSummary = "Проверить состояние отдельно"
            )
        )
        writeLegacyV2(legacy, expected)
        try {
            // Simulate the committed import transaction before the read-only quick_check/final marker.
            val seedHelper = SpiderTaskHistoryDatabase(context, dbName)
            val seedDb = seedHelper.writableDatabase
            seedDb.beginTransaction()
            try {
                val task = expected.single()
                val taskValues = ContentValues().apply {
                    put("task_id", task.id); put("goal", task.goal); put("status", task.status.name)
                    put("created_at", task.createdAtEpochMs); put("updated_at", task.updatedAtEpochMs)
                    put("last_error_code", task.lastErrorCode); putNull("resume_status")
                    put("result_summary", task.resultSummary); putNull("workspace_scope_id")
                }
                seedDb.insertOrThrow("task_history", null, taskValues)
                task.steps.forEachIndexed { position, step ->
                    val stepValues = ContentValues().apply {
                        put("task_id", task.id); put("position", position); put("step_id", step.id)
                        put("title", step.title); put("tool_id", step.toolId?.name)
                        put("status", step.status.name); put("result_summary", step.resultSummary)
                    }
                    seedDb.insertOrThrow("task_steps", null, stepValues)
                }
                seedDb.insertOrThrow("task_history_metadata", null, ContentValues().apply {
                    put("key", "legacy_binary_import_v1"); put("value", "pending:1")
                })
                seedDb.setTransactionSuccessful()
            } finally {
                seedDb.endTransaction()
                seedHelper.close()
            }

            val recovered = SqliteSpiderTaskRepository(context, legacy, dbName)
            assertEquals(expected, recovered.all())
            assertFalse("the old file is only archived after the pending import is checked", legacy.exists())
            assertTrue(File(legacy.parentFile, legacy.name + ".migrated-sqlite-v1").exists())
            assertEquals(1, recovered.count())
        } finally {
            legacy.delete()
            File(legacy.parentFile, legacy.name + ".migrated-sqlite-v1").delete()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun recentAndCountQueriesDoNotRequireHistoryTruncation() {
        val legacy = File(context.cacheDir, "empty-task-history-$suffix.spider")
        val repo = SqliteSpiderTaskRepository(context, legacy, dbName)
        try {
            repeat(120) { index ->
                val id = "task_$index"
                repo.save(SpiderAgentTask(
                    id = id,
                    goal = "Задача $index",
                    createdAtEpochMs = index.toLong(),
                    updatedAtEpochMs = (index * 10L),
                    steps = if (index % 2 == 0) listOf(SpiderPlanStep("step_$index", "Шаг", SpiderToolId.LIST_WORKSPACE_FILES)) else emptyList()
                ))
            }
            assertEquals(120, repo.count())
            assertEquals((119 downTo 100).map { "task_$it" }, repo.recent(20).map { it.id })
            assertEquals("task_119", repo.find("task_119")?.id)
            val updated = repo.find("task_3")!!.copy(goal = "Обновлённая цель", updatedAtEpochMs = 50_000L)
            repo.save(updated)
            assertEquals(120, repo.count())
            assertEquals("Обновлённая цель", repo.recent(1).single().goal)
            assertEquals(1, repo.all().count { it.id == "task_3" })
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun unknownLegacySchemaFailsClosedAndPreservesOriginalBytes() {
        val legacy = File(context.cacheDir, "future-task-history-$suffix.spider")
        DataOutputStream(FileOutputStream(legacy)).use { out ->
            out.writeInt(FileSpiderTaskRepository.MAGIC)
            out.writeInt(FileSpiderTaskRepository.SCHEMA_VERSION + 1)
            out.writeInt(0)
        }
        val original = legacy.readBytes()
        try {
            val repo = SqliteSpiderTaskRepository(context, legacy, dbName)
            val failure = runCatching { repo.all() }.exceptionOrNull()
            assertNotNull("future schema must block migration", failure)
            assertTrue(failure is IOException)
            assertTrue(legacy.exists())
            assertTrue(original.contentEquals(legacy.readBytes()))
            assertFalse(File(legacy.parentFile, legacy.name + ".migrated-sqlite-v1").exists())
        } finally {
            legacy.delete()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun databaseWithFutureSchemaIsNotDowngradedOrOverwritten() {
        val legacy = File(context.cacheDir, "future-db-legacy-$suffix.spider")
        val databaseFile = context.getDatabasePath(dbName)
        databaseFile.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        raw.execSQL("PRAGMA user_version = 2")
        raw.close()
        try {
            val repo = SqliteSpiderTaskRepository(context, legacy, dbName)
            val failure = runCatching { repo.all() }.exceptionOrNull()
            assertNotNull("future SQLite schema must fail closed", failure)
            assertTrue(databaseFile.exists())
            val verify = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val version = verify.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            }
            verify.close()
            assertEquals("future DB schema must remain at version 2", 2, version)
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun writeLegacyV2(file: File, tasks: List<SpiderAgentTask>) {
        file.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(FileSpiderTaskRepository.MAGIC)
            out.writeInt(2)
            out.writeInt(tasks.size)
            fun text(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            fun optional(value: String?) {
                out.writeBoolean(value != null)
                if (value != null) text(value)
            }
            tasks.forEach { task ->
                text(task.id); text(task.goal); text(task.status.name)
                out.writeLong(task.createdAtEpochMs); out.writeLong(task.updatedAtEpochMs)
                optional(task.lastErrorCode); optional(task.resumeStatus?.name); optional(task.resultSummary)
                optional(task.workspaceScopeId)
                out.writeInt(task.steps.size)
                task.steps.forEach { step ->
                    text(step.id); text(step.title); optional(step.toolId?.name); text(step.status.name); optional(step.resultSummary)
                }
            }
        }
    }
}

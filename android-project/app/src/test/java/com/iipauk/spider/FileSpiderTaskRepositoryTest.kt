package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class FileSpiderTaskRepositoryTest {
    private fun temporaryDirectory(): File = File(System.getProperty("java.io.tmpdir"), "spider-task-store-test-${System.nanoTime()}").apply {
        check(mkdirs())
    }

    @Test
    fun persistsTaskAndPlanAcrossRepositoryInstances() {
        val dir = temporaryDirectory()
        try {
            val file = File(dir, "tasks.spider")
            val original = SpiderAgentTask(
                id = "task-1",
                goal = "Создать HTML-демо",
                status = SpiderTaskStatus.VERIFYING,
                steps = listOf(
                    SpiderPlanStep("step-1", "Список файлов", SpiderToolId.LIST_WORKSPACE_FILES, SpiderStepStatus.SUCCEEDED, "2 файла"),
                    SpiderPlanStep("step-2", "Создание демо", SpiderToolId.WRITE_WORKSPACE_FILE, SpiderStepStatus.SUCCEEDED, "index.html записан")
                ),
                createdAtEpochMs = 100L,
                updatedAtEpochMs = 240L,
                lastErrorCode = null,
                resumeStatus = null,
                resultSummary = "Ожидается независимая проверка"
            )
            FileSpiderTaskRepository(file).save(original)

            val reopened = FileSpiderTaskRepository(file)
            assertEquals(original, reopened.find("task-1"))
            assertEquals(listOf(original), reopened.all())
            assertTrue(file.length() > 12L)
            assertNull(reopened.recoveryNotice)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun savesUpdatesWithoutDroppingExistingTasks() {
        val dir = temporaryDirectory()
        try {
            val repo = FileSpiderTaskRepository(File(dir, "tasks.spider"))
            repo.save(SpiderAgentTask(id = "a", goal = "Первая задача", createdAtEpochMs = 1L, updatedAtEpochMs = 1L))
            repo.save(SpiderAgentTask(id = "b", goal = "Вторая задача", createdAtEpochMs = 2L, updatedAtEpochMs = 2L))
            repo.save(SpiderAgentTask(id = "a", goal = "Первая задача обновлена", updatedAtEpochMs = 3L))

            val all = FileSpiderTaskRepository(File(dir, "tasks.spider")).all()
            assertEquals(listOf("a", "b"), all.map { it.id })
            assertEquals("Первая задача обновлена", all.first().goal)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptStoreIsPreservedAndFreshStoreCanBeWritten() {
        val dir = temporaryDirectory()
        try {
            val file = File(dir, "tasks.spider")
            val corruptBytes = "not a spider task store".toByteArray(StandardCharsets.UTF_8)
            file.writeBytes(corruptBytes)

            val repo = FileSpiderTaskRepository(file)
            assertTrue(repo.all().isEmpty())
            assertNotNull(repo.recoveryNotice)
            val backups = dir.listFiles().orEmpty().filter { it.name.startsWith("tasks.spider.corrupt-") }
            assertEquals(1, backups.size)
            assertTrue(corruptBytes.contentEquals(backups.single().readBytes()))

            repo.save(SpiderAgentTask(id = "fresh", goal = "Новая задача"))
            assertEquals("Новая задача", FileSpiderTaskRepository(file).find("fresh")?.goal)
            assertNotNull(repo.recoveryNotice)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun futureSchemaIsNotRenamedOrOverwritten() {
        val dir = temporaryDirectory()
        try {
            val file = File(dir, "tasks.spider")
            DataOutputStream(FileOutputStream(file)).use { out ->
                out.writeInt(FileSpiderTaskRepository.MAGIC)
                out.writeInt(FileSpiderTaskRepository.SCHEMA_VERSION + 1)
                out.writeInt(0)
            }
            val original = file.readBytes()
            val repo = FileSpiderTaskRepository(file)
            val failure = runCatching { repo.all() }.exceptionOrNull()
            assertTrue("Expected unsupported schema failure", failure is IOException)
            assertTrue(file.exists())
            assertTrue(original.contentEquals(file.readBytes()))
            assertTrue(dir.listFiles().orEmpty().none { it.name.contains(".corrupt-") })
            assertFalse(repo.recoveryNotice != null)
        } finally {
            dir.deleteRecursively()
        }
    }
}

package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceFileListHandlerTest {
    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "spider-workspace-list-${System.nanoTime()}").apply {
        check(mkdirs())
    }

    @Test
    fun listsNamesButDoesNotReadFileContents() {
        val temp = tempDir()
        try {
            val root = File(temp, "workspace").apply { mkdirs() }
            File(root, "index.html").writeText("DO_NOT_EXPOSE_THIS_SECRET_CONTENT")
            File(root, ".spider-workspace.json").writeText("INTERNAL_METADATA_CONTENT")
            val src = File(root, "src").apply { mkdirs() }
            File(src, "main.kt").writeText("fun main() = Unit")

            val handler = WorkspaceFileListHandler { id -> if (id == "workspace-1") root else null }
            val result = handler.execute(SpiderToolRequest("req-1", "task-1", SpiderToolId.LIST_WORKSPACE_FILES, "List selected workspace", "workspace-1"))

            assertTrue(result.succeeded)
            assertEquals("workspace_listed", result.resultCode)
            assertTrue(result.summary.contains("index.html"))
            assertTrue(result.summary.contains("src/"))
            val evidence = WorkspaceListingEvidence.parse(result.verificationEvidence)
            assertNotNull(evidence)
            assertEquals(3, evidence!!.entryCount)
            assertFalse(evidence.truncated)
            assertFalse(result.summary.contains("DO_NOT_EXPOSE_THIS_SECRET_CONTENT"))
            assertFalse(result.summary.contains(".spider-workspace.json"))
            assertFalse(result.summary.contains("INTERNAL_METADATA_CONTENT"))
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun doesNotFollowSymlinkOutsideWorkspace() {
        val temp = tempDir()
        try {
            val root = File(temp, "workspace").apply { mkdirs() }
            val outside = File(temp, "outside").apply { mkdirs() }
            File(outside, "private.txt").writeText("private")
            try {
                Files.createSymbolicLink(File(root, "outside-link").toPath(), outside.toPath())
            } catch (_: Exception) {
                // Symlink creation may be unavailable under restricted test runners.
                return
            }
            val result = WorkspaceFileListHandler { if (it == "workspace-1") root else null }
                .execute(SpiderToolRequest("req-2", "task-1", SpiderToolId.LIST_WORKSPACE_FILES, "List files", "workspace-1"))
            assertTrue(result.succeeded)
            assertFalse(result.summary.contains("private.txt"))
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun rejectsMissingWorkspaceIdentity() {
        val result = WorkspaceFileListHandler { null }
            .execute(SpiderToolRequest("req-3", "task-1", SpiderToolId.LIST_WORKSPACE_FILES, "List files"))
        assertFalse(result.succeeded)
        assertEquals("workspace_id_required", result.resultCode)
    }
}

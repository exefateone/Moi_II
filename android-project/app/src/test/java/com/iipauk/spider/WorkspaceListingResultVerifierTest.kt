package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspaceListingResultVerifierTest {
    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "spider-verifier-${System.nanoTime()}").apply {
        check(mkdirs())
    }

    private fun callResult(handlerResult: SpiderToolHandlerResult, auditRecorded: Boolean = true) = SpiderToolCallResult(
        status = if (handlerResult.succeeded && auditRecorded) SpiderToolCallStatus.SUCCEEDED else SpiderToolCallStatus.FAILED,
        disposition = SpiderPolicyDisposition.ALLOW,
        risk = SpiderToolRisk.LOW,
        resultCode = handlerResult.resultCode,
        message = handlerResult.summary,
        auditRecorded = auditRecorded,
        verificationEvidence = handlerResult.verificationEvidence
    )

    @Test
    fun verifiesInventoryAgainstFreshFilesystemWalk() {
        val temp = tempDir()
        try {
            val root = File(temp, "workspace").apply { mkdirs() }
            File(root, "index.html").writeText("content is not read by the listing tool")
            File(root, ".spider-workspace.json").writeText("hidden internal marker")
            File(root, "src").apply { mkdirs() }
            File(root, "src/main.kt").writeText("fun main() = Unit")
            val listing = WorkspaceFileListHandler { if (it == "workspace-1") root else null }
                .execute(SpiderToolRequest("req-1", "task-1", SpiderToolId.LIST_WORKSPACE_FILES, "List files", "workspace-1"))
            val result = WorkspaceListingResultVerifier { if (it == "workspace-1") root else null }
                .verify("workspace-1", callResult(listing))
            assertTrue(result.verified)
            assertEquals("workspace_listing_verified", result.code)
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun failsWhenWorkspaceInventoryChangesAfterToolResult() {
        val temp = tempDir()
        try {
            val root = File(temp, "workspace").apply { mkdirs() }
            File(root, "before.txt").writeText("safe")
            val listing = WorkspaceFileListHandler { root }
                .execute(SpiderToolRequest("req-1", "task-1", SpiderToolId.LIST_WORKSPACE_FILES, "List files", "workspace-1"))
            File(root, "after.txt").writeText("new file appeared")
            val result = WorkspaceListingResultVerifier { root }.verify("workspace-1", callResult(listing))
            assertFalse(result.verified)
            assertEquals("verification_inventory_mismatch", result.code)
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun failsClosedWhenAuditOrEvidenceIsMissing() {
        val temp = tempDir()
        try {
            val root = File(temp, "workspace").apply { mkdirs() }
            File(root, "a.txt").writeText("x")
            val listing = WorkspaceFileListHandler { root }
                .execute(SpiderToolRequest("req-1", "task-1", SpiderToolId.LIST_WORKSPACE_FILES, "List files", "workspace-1"))
            val verifier = WorkspaceListingResultVerifier { root }
            val noAudit = verifier.verify("workspace-1", callResult(listing, auditRecorded = false))
            assertFalse(noAudit.verified)
            assertEquals("verification_tool_not_succeeded", noAudit.code)
            val noEvidence = verifier.verify("workspace-1", callResult(listing).copy(verificationEvidence = null))
            assertFalse(noEvidence.verified)
            assertEquals("verification_evidence_missing", noEvidence.code)
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun rejectsWrongWorkspaceEvidenceAndTruncatedInventory() {
        val evidence = WorkspaceListingEvidence.fromPaths("other-workspace", listOf("a.txt"), false)!!.encode()
        val toolResult = SpiderToolHandlerResult(true, "workspace_listed", "Найдено элементов: 1. Элементы: a.txt", evidence)
        val mismatch = WorkspaceListingResultVerifier { File(".") }.verify("workspace-1", callResult(toolResult))
        assertFalse(mismatch.verified)
        assertEquals("verification_evidence_workspace_mismatch", mismatch.code)

        val truncatedEvidence = WorkspaceListingEvidence.fromPaths("workspace-1", listOf("a.txt"), true)!!.encode()
        val truncatedToolResult = toolResult.copy(verificationEvidence = truncatedEvidence)
        val truncated = WorkspaceListingResultVerifier { File(".") }.verify("workspace-1", callResult(truncatedToolResult))
        assertFalse(truncated.verified)
        assertEquals("verification_inventory_truncated", truncated.code)
    }
}

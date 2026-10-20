package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiderToolBrokerTest {
    private class RecordingAudit(var allowWrites: Boolean = true) : SpiderAuditSink {
        val results = mutableListOf<String>()
        override fun append(
            taskId: String,
            requestId: String,
            toolId: SpiderToolId,
            decision: SpiderPolicyDecision,
            resultCode: String,
            summary: String
        ): Boolean {
            if (!allowWrites) return false
            results += "$resultCode:${decision.disposition}"
            return true
        }
    }

    private class CountingHandler : SpiderToolHandler {
        override val toolId = SpiderToolId.LIST_WORKSPACE_FILES
        override val displayName = "list workspace files"
        var calls = 0
        override fun execute(request: SpiderToolRequest): SpiderToolHandlerResult {
            calls++
            return SpiderToolHandlerResult(true, "ok", "Listed files successfully")
        }
    }

    private fun request(id: String, tool: SpiderToolId, summary: String = "Read project files") =
        SpiderToolRequest(id, "task-1", tool, summary)

    @Test
    fun policyApprovalIsRequiredBeforeHandlerDispatch() {
        val audit = RecordingAudit()
        val networkHandler = object : SpiderToolHandler {
            override val toolId = SpiderToolId.SEARCH_PUBLIC_WEB
            override val displayName = "public web search"
            var calls = 0
            override fun execute(request: SpiderToolRequest): SpiderToolHandlerResult {
                calls++
                return SpiderToolHandlerResult(true, "searched", "Search complete")
            }
        }
        val broker = SpiderToolBroker(listOf(networkHandler), audit)
        val result = broker.execute(request("network-1", SpiderToolId.SEARCH_PUBLIC_WEB))
        assertEquals(SpiderToolCallStatus.WAITING_FOR_APPROVAL, result.status)
        assertEquals(0, networkHandler.calls)
    }

    @Test
    fun doesNotAskForApprovalWhenNoHandlerExists() {
        val audit = RecordingAudit()
        val broker = SpiderToolBroker(emptyList(), audit)
        val result = broker.execute(request("network-2", SpiderToolId.SEARCH_PUBLIC_WEB))
        assertEquals(SpiderToolCallStatus.FAILED, result.status)
        assertEquals("tool_handler_missing", result.resultCode)
    }

    @Test
    fun successfulTypedHandlerIsExecutedAndAudited() {
        val audit = RecordingAudit()
        val handler = CountingHandler()
        val broker = SpiderToolBroker(listOf(handler), audit)
        val result = broker.execute(request("read-1", SpiderToolId.LIST_WORKSPACE_FILES))
        assertEquals(SpiderToolCallStatus.SUCCEEDED, result.status)
        assertTrue(result.auditRecorded)
        assertEquals(1, handler.calls)
        assertEquals(2, audit.results.size) // pre-execution and final outcome records
    }

    @Test
    fun requestIdCannotReplayACompletedToolCall() {
        val handler = CountingHandler()
        val broker = SpiderToolBroker(listOf(handler), RecordingAudit())
        assertEquals(SpiderToolCallStatus.SUCCEEDED, broker.execute(request("read-1", handler.toolId)).status)
        val replay = broker.execute(request("read-1", handler.toolId))
        assertEquals(SpiderToolCallStatus.DENIED, replay.status)
        assertEquals("duplicate_request_id", replay.resultCode)
        assertEquals(1, handler.calls)
    }

    @Test
    fun missingHandlerFailsClosed() {
        val broker = SpiderToolBroker(emptyList(), RecordingAudit())
        val result = broker.execute(request("write-1", SpiderToolId.READ_WORKSPACE_FILE))
        assertEquals(SpiderToolCallStatus.FAILED, result.status)
        assertEquals("tool_handler_missing", result.resultCode)
    }

    @Test
    fun failedPreExecutionAuditPreventsSideEffect() {
        val audit = RecordingAudit(allowWrites = false)
        val handler = CountingHandler()
        val broker = SpiderToolBroker(listOf(handler), audit)
        val result = broker.execute(request("read-1", handler.toolId))
        assertEquals(SpiderToolCallStatus.FAILED, result.status)
        assertEquals("audit_unavailable", result.resultCode)
        assertEquals(0, handler.calls)
    }

    @Test
    fun approvedSensitiveRequestExecutesOnlyOnce() {
        val audit = RecordingAudit()
        val writeHandler = object : SpiderToolHandler {
            override val toolId = SpiderToolId.WRITE_WORKSPACE_FILE
            override val displayName = "write workspace file"
            var calls = 0
            override fun execute(request: SpiderToolRequest): SpiderToolHandlerResult {
                calls++
                return SpiderToolHandlerResult(true, "written", "File was written")
            }
        }
        val broker = SpiderToolBroker(listOf(writeHandler), audit)
        val req = request("write-1", writeHandler.toolId, "Write file through workspace API")
        assertEquals(SpiderToolCallStatus.WAITING_FOR_APPROVAL, broker.execute(req).status)
        val approvedContext = SpiderPolicyContext(approvedRequestIds = setOf(req.requestId))
        assertEquals(SpiderToolCallStatus.SUCCEEDED, broker.execute(req, approvedContext).status)
        assertEquals(SpiderToolCallStatus.DENIED, broker.execute(req, approvedContext).status)
        assertEquals(1, writeHandler.calls)
    }

    @Test
    fun redactorRemovesCommonSecretPatterns() {
        val redacted = SpiderAuditRedactor.redact("api_key=abc123 and Authorization: Bearer abcdef")
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("abcdef"))
        assertTrue(redacted.contains("[REDACTED]"))
    }
}

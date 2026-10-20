package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpiderPolicyEngineTest {
    private val engine = SpiderPolicyEngine()

    private fun request(id: String, tool: SpiderToolId, summary: String = "Test action") =
        SpiderToolRequest(requestId = id, taskId = "task-1", toolId = tool, summary = summary)

    @Test
    fun localReadCanPassWithoutApproval() {
        val result = engine.evaluate(request("r-1", SpiderToolId.LIST_WORKSPACE_FILES))
        assertEquals(SpiderPolicyDisposition.ALLOW, result.disposition)
        assertEquals(SpiderToolRisk.LOW, result.risk)
    }

    @Test
    fun networkRequestNeedsPermissionOrPerRequestApproval() {
        val req = request("r-2", SpiderToolId.SEARCH_PUBLIC_WEB)
        assertEquals(SpiderPolicyDisposition.REQUIRE_APPROVAL, engine.evaluate(req).disposition)
        val approvedOnce = SpiderPolicyContext(approvedRequestIds = setOf("r-2"))
        assertEquals(SpiderPolicyDisposition.ALLOW, engine.evaluate(req, approvedOnce).disposition)
    }

    @Test
    fun writeAndDeleteRequireFreshRequestApproval() {
        val write = request("r-write", SpiderToolId.WRITE_WORKSPACE_FILE)
        val delete = request("r-delete", SpiderToolId.DELETE_WORKSPACE_FILE)
        assertEquals(SpiderPolicyDisposition.REQUIRE_APPROVAL, engine.evaluate(write).disposition)
        assertEquals(SpiderPolicyDisposition.REQUIRE_APPROVAL, engine.evaluate(delete).disposition)
        assertEquals(SpiderPolicyDisposition.ALLOW, engine.evaluate(delete, SpiderPolicyContext(approvedRequestIds = setOf("r-delete"))).disposition)
        assertNotEquals(SpiderPolicyDisposition.ALLOW, engine.evaluate(delete, SpiderPolicyContext(approvedRequestIds = setOf("r-write"))).disposition)
    }

    @Test
    fun malformedRequestsAreDenied() {
        val emptyId = request("", SpiderToolId.READ_WORKSPACE_FILE)
        val emptySummary = request("r-empty", SpiderToolId.READ_WORKSPACE_FILE, "")
        assertEquals(SpiderPolicyDisposition.DENY, engine.evaluate(emptyId).disposition)
        assertEquals(SpiderPolicyDisposition.DENY, engine.evaluate(emptySummary).disposition)
    }
}

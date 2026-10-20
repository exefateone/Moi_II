package com.iipauk.spider

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpiderProviderPlannerInstrumentedTest {
    private val planner = HttpSpiderTaskPlanner(
        SpiderProviderConnection(
            "https://agent.example.com/api/agent/plan",
            "test-client-token-with-at-least-24-chars"
        )
    )

    @Test
    fun acceptsOnlyKnownTypedToolAndResetsUntrustedStepState() {
        val response = """{"status":"ok","task_id":"task-1","steps":[{"id":"inspect","title":"Посмотреть структуру","tool_id":"LIST_WORKSPACE_FILES","status":"SUCCEEDED","result_summary":"fake success"}]}"""
        val steps = planner.parsePlanResponse(response, "task-1")
        assertEquals(1, steps.size)
        assertEquals(SpiderToolId.LIST_WORKSPACE_FILES, steps.single().toolId)
        assertEquals(SpiderStepStatus.PENDING, steps.single().status)
        assertEquals(null, steps.single().resultSummary)
    }

    @Test
    fun rejectsUnknownToolAndTaskIdMismatch() {
        val unknownTool = """{"status":"ok","task_id":"task-1","steps":[{"id":"s1","title":"Run shell","tool_id":"EXECUTE_SHELL"}]}"""
        val wrongTask = """{"status":"ok","task_id":"another-task","steps":[{"id":"s1","title":"List files","tool_id":"LIST_WORKSPACE_FILES"}]}"""
        assertThrows(IllegalArgumentException::class.java) { planner.parsePlanResponse(unknownTool, "task-1") }
        assertThrows(IllegalArgumentException::class.java) { planner.parsePlanResponse(wrongTask, "task-1") }
    }

    @Test
    fun rejectsDuplicateStepIdsAndTooManySteps() {
        val duplicates = """{"status":"ok","task_id":"task-1","steps":[{"id":"s1","title":"A","tool_id":"LIST_WORKSPACE_FILES"},{"id":"s1","title":"B","tool_id":"LIST_WORKSPACE_FILES"}]}"""
        val tooMany = (1..7).joinToString(",") { i -> """{"id":"s$i","title":"List $i","tool_id":"LIST_WORKSPACE_FILES"}""" }
        val tooManyResponse = """{"status":"ok","task_id":"task-1","steps":[$tooMany]}"""
        assertThrows(IllegalArgumentException::class.java) { planner.parsePlanResponse(duplicates, "task-1") }
        assertThrows(IllegalArgumentException::class.java) { planner.parsePlanResponse(tooManyResponse, "task-1") }
    }
}

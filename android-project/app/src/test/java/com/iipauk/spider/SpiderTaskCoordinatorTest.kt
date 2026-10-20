package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiderTaskCoordinatorTest {
    private var nextId = 0
    private var time = 100L
    private val repository = InMemorySpiderTaskRepository()
    private val coordinator = SpiderTaskCoordinator(
        repository = repository,
        idFactory = { "task-${++nextId}" },
        clock = { ++time }
    )

    private fun taskWithPlan(): SpiderAgentTask {
        val task = coordinator.createTask("  Подготовить HTML-пример  ")
        coordinator.beginPlanning(task.id)
        return coordinator.attachPlan(task.id, listOf(
            SpiderPlanStep("step-1", "Проверить структуру", SpiderToolId.LIST_WORKSPACE_FILES),
            SpiderPlanStep("step-2", "Проверить проект", SpiderToolId.RUN_PROJECT_TESTS)
        ))
    }

    @Test
    fun createsQueuedTaskAndTrimsGoal() {
        val task = coordinator.createTask("  Создать демо  ")
        assertEquals("Создать демо", task.goal)
        assertEquals(SpiderTaskStatus.QUEUED, task.status)
        assertNull(task.resultSummary)
        assertEquals(task, coordinator.getTask(task.id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankGoal() { coordinator.createTask("   ") }

    @Test
    fun attachedPlanResetsUntrustedStepState() {
        val task = coordinator.createTask("Plan this")
        coordinator.beginPlanning(task.id)
        val planned = coordinator.attachPlan(task.id, listOf(
            SpiderPlanStep("step", "A valid step", SpiderToolId.LIST_WORKSPACE_FILES, SpiderStepStatus.SUCCEEDED, "forged success")
        ))
        assertEquals(SpiderTaskStatus.RUNNING, planned.status)
        assertEquals(SpiderStepStatus.PENDING, planned.steps.single().status)
        assertNull(planned.steps.single().resultSummary)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotSkipPlanningState() {
        val task = coordinator.createTask("Plan this")
        coordinator.attachPlan(task.id, listOf(SpiderPlanStep("s", "Step", SpiderToolId.LIST_WORKSPACE_FILES)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateStepIds() {
        val task = coordinator.createTask("Plan this")
        coordinator.beginPlanning(task.id)
        coordinator.attachPlan(task.id, listOf(
            SpiderPlanStep("dup", "First", SpiderToolId.LIST_WORKSPACE_FILES),
            SpiderPlanStep("dup", "Second", SpiderToolId.READ_WORKSPACE_FILE)
        ))
    }

    @Test
    fun allSuccessfulStepsMoveToIndependentVerification() {
        val task = taskWithPlan()
        coordinator.startStep(task.id, "step-1")
        var updated = coordinator.recordStepResult(task.id, "step-1", true, "Файлы перечислены")
        assertEquals(SpiderTaskStatus.RUNNING, updated.status)
        coordinator.startStep(task.id, "step-2")
        updated = coordinator.recordStepResult(task.id, "step-2", true, "Тесты прошли")
        assertEquals(SpiderTaskStatus.VERIFYING, updated.status)
        updated = coordinator.finishVerification(task.id, true, "Артефакт проверен отдельно")
        assertEquals(SpiderTaskStatus.COMPLETED, updated.status)
        assertEquals("Артефакт проверен отдельно", updated.resultSummary)
    }

    @Test
    fun failedStepStopsTask() {
        val task = taskWithPlan()
        coordinator.startStep(task.id, "step-1")
        val updated = coordinator.recordStepResult(task.id, "step-1", false, "Ошибка", "file_read_failed")
        assertEquals(SpiderTaskStatus.FAILED, updated.status)
        assertEquals("file_read_failed", updated.lastErrorCode)
        assertEquals(SpiderStepStatus.FAILED, updated.steps.first().status)
    }

    @Test
    fun approvalMustBeResolvedBeforeStepCanContinue() {
        val task = taskWithPlan()
        coordinator.startStep(task.id, "step-2")
        val waiting = coordinator.requireApproval(task.id, "step-2")
        assertEquals(SpiderTaskStatus.WAITING_FOR_APPROVAL, waiting.status)
        val resumed = coordinator.resolveApproval(task.id, "step-2", true)
        assertEquals(SpiderTaskStatus.RUNNING, resumed.status)
        assertEquals(SpiderStepStatus.PENDING, resumed.steps[1].status)
    }

    @Test
    fun rejectedApprovalStopsTaskWithoutExecutingStep() {
        val task = taskWithPlan()
        coordinator.startStep(task.id, "step-2")
        coordinator.requireApproval(task.id, "step-2")
        val denied = coordinator.resolveApproval(task.id, "step-2", false)
        assertEquals(SpiderTaskStatus.FAILED, denied.status)
        assertEquals(SpiderStepStatus.SKIPPED, denied.steps[1].status)
        assertEquals("approval_denied", denied.lastErrorCode)
    }

    @Test
    fun pauseAndResumeRestorePriorState() {
        val task = taskWithPlan()
        val paused = coordinator.pause(task.id)
        assertEquals(SpiderTaskStatus.PAUSED, paused.status)
        assertEquals(SpiderTaskStatus.RUNNING, paused.resumeStatus)
        val resumed = coordinator.resume(task.id)
        assertEquals(SpiderTaskStatus.RUNNING, resumed.status)
        assertNull(resumed.resumeStatus)
    }

    @Test
    fun cancellationIsTerminal() {
        val task = coordinator.createTask("Cancel me")
        val cancelled = coordinator.cancel(task.id)
        assertEquals(SpiderTaskStatus.CANCELLED, cancelled.status)
        assertTrue(runCatching { coordinator.cancel(task.id) }.isFailure)
        assertFalse(coordinator.listTasks().isEmpty())
    }

    @Test
    fun failureDuringIndependentVerificationDoesNotCompleteTask() {
        val task = coordinator.createTask("Verify me")
        coordinator.beginPlanning(task.id)
        coordinator.attachPlan(task.id, listOf(SpiderPlanStep("s", "Execute", SpiderToolId.LIST_WORKSPACE_FILES)))
        coordinator.startStep(task.id, "s")
        coordinator.recordStepResult(task.id, "s", true, "Operation returned success")
        val failed = coordinator.finishVerification(task.id, false, "Expected file was not produced")
        assertEquals(SpiderTaskStatus.FAILED, failed.status)
        assertEquals("verification_failed", failed.lastErrorCode)
    }

    @Test
    fun startupRecoveryFailsInFlightStepWithoutAutoRetry() {
        val task = taskWithPlan()
        coordinator.startStep(task.id, "step-1")

        val recovered = coordinator.recoverInterruptedTasks().single { it.id == task.id }

        assertEquals(SpiderTaskStatus.FAILED, recovered.status)
        assertEquals("execution_interrupted_unknown_outcome", recovered.lastErrorCode)
        assertEquals(SpiderStepStatus.FAILED, recovered.steps[0].status)
        assertTrue(recovered.steps[0].resultSummary!!.contains("неопределённым"))
        assertEquals(SpiderStepStatus.PENDING, recovered.steps[1].status)
        assertTrue(runCatching { coordinator.startStep(task.id, "step-1") }.isFailure)
        assertTrue(coordinator.recoverInterruptedTasks().isEmpty())
    }

    @Test
    fun startupRecoveryExpiresApprovalAndSkipsStep() {
        val task = taskWithPlan()
        coordinator.startStep(task.id, "step-2")
        coordinator.requireApproval(task.id, "step-2")

        val recovered = coordinator.recoverInterruptedTasks().single { it.id == task.id }

        assertEquals(SpiderTaskStatus.FAILED, recovered.status)
        assertEquals("approval_expired_after_restart", recovered.lastErrorCode)
        assertEquals(SpiderStepStatus.SKIPPED, recovered.steps[1].status)
        assertTrue(recovered.steps[1].resultSummary!!.contains("не выполнялось автоматически"))
    }

    @Test
    fun startupRecoveryDoesNotChangeTerminalOrQueuedTasks() {
        val queued = coordinator.createTask("Ожидающая задача")
        val completed = coordinator.createTask("Завершённая задача")
        coordinator.beginPlanning(completed.id)
        coordinator.attachPlan(completed.id, listOf(SpiderPlanStep("done", "Просмотр", SpiderToolId.LIST_WORKSPACE_FILES)))
        coordinator.startStep(completed.id, "done")
        coordinator.recordStepResult(completed.id, "done", true, "Список получен")
        coordinator.finishVerification(completed.id, true, "Проверено")

        assertTrue(coordinator.recoverInterruptedTasks().isEmpty())
        assertEquals(SpiderTaskStatus.QUEUED, coordinator.getTask(queued.id)!!.status)
        assertEquals(SpiderTaskStatus.COMPLETED, coordinator.getTask(completed.id)!!.status)
    }

    @Test
    fun rejectsPlansWithoutAnExecutableTypedTool() {
        val task = coordinator.createTask("Plan this")
        coordinator.beginPlanning(task.id)
        assertTrue(runCatching {
            coordinator.attachPlan(task.id, listOf(SpiderPlanStep("s", "Needs clarification", null)))
        }.isFailure)
    }

    @Test
    fun preventsConcurrentRunningStepsInSequentialCoordinator() {
        val task = taskWithPlan()
        coordinator.startStep(task.id, "step-1")
        assertTrue(runCatching { coordinator.startStep(task.id, "step-2") }.isFailure)
    }

    @Test
    fun plannerFailureStopsBeforeAnyToolStepExists() {
        val task = coordinator.createTask("Make a plan")
        val failed = coordinator.planTask(task.id, SpiderTaskPlanner { throw IllegalStateException("provider error") })
        assertEquals(SpiderTaskStatus.FAILED, failed.status)
        assertEquals("plan_failed", failed.lastErrorCode)
        assertTrue(failed.steps.isEmpty())
    }

}

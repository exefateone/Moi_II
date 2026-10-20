package com.iipauk.spider

import java.util.UUID

/** Small persistence boundary. Android persistence can replace this without changing task rules. */
interface SpiderTaskRepository {
    fun find(taskId: String): SpiderAgentTask?
    fun save(task: SpiderAgentTask)
    fun all(): List<SpiderAgentTask>

    /** Bounded, newest-first history query. Implementations should avoid loading the full history. */
    fun recent(limit: Int = 30): List<SpiderAgentTask> = all()
        .sortedWith(compareByDescending<SpiderAgentTask> { it.updatedAtEpochMs }
            .thenByDescending { it.createdAtEpochMs }.thenByDescending { it.id })
        .take(limit.coerceIn(0, 1000))

    /** Tasks that could require safe startup recovery; storage implementations should filter in SQL. */
    fun recoverableForStartup(): List<SpiderAgentTask> = all().filter { task ->
        val state = if (task.status == SpiderTaskStatus.PAUSED) task.resumeStatus else task.status
        state in setOf(SpiderTaskStatus.PLANNING, SpiderTaskStatus.RUNNING, SpiderTaskStatus.WAITING_FOR_APPROVAL, SpiderTaskStatus.VERIFYING)
    }

    /** Total rows for the history summary; storage implementations may answer without materializing rows. */
    fun count(): Int = all().size
}

/** Thread-safe prototype repository. It is process-memory only and must not be mistaken for durable storage. */
class InMemorySpiderTaskRepository : SpiderTaskRepository {
    private val tasks = linkedMapOf<String, SpiderAgentTask>()

    @Synchronized
    override fun find(taskId: String): SpiderAgentTask? = tasks[taskId]

    @Synchronized
    override fun save(task: SpiderAgentTask) {
        tasks[task.id] = task
    }

    @Synchronized
    override fun all(): List<SpiderAgentTask> = tasks.values.sortedBy { it.createdAtEpochMs }
}

/** Model-provider-independent port implemented by the local llama.cpp planner and explicit cloud planner. */
fun interface SpiderTaskPlanner {
    fun proposePlan(task: SpiderAgentTask): List<SpiderPlanStep>
}

/**
 * Enforces task/step state transitions centrally. This component never runs commands and never trusts
 * a model-provided status: imported plan steps are reset to PENDING before they are saved.
 */
class SpiderTaskCoordinator(
    private val repository: SpiderTaskRepository,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Captures terminal outcomes for learning memory only after the durable task save succeeds. */
    private val terminalTaskObserver: ((SpiderAgentTask) -> Unit)? = null
) {
    @Volatile var terminalObserverError: String? = null
        private set
    @Synchronized
    fun createTask(goal: String, workspaceScopeId: String? = null): SpiderAgentTask {
        val cleanGoal = goal.trim()
        require(cleanGoal.isNotEmpty()) { "Task goal cannot be empty" }
        require(cleanGoal.length <= MAX_GOAL_LENGTH) { "Task goal is too long" }
        val id = idFactory().trim()
        require(id.isNotEmpty() && id.length <= 120) { "Task ID is invalid" }
        require(repository.find(id) == null) { "Task ID already exists" }
        val cleanScope = workspaceScopeId?.trim()?.also {
            require(it.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) { "Workspace scope ID is invalid" }
        }
        val createdAt = clock()
        return SpiderAgentTask(
            id = id,
            goal = cleanGoal,
            status = SpiderTaskStatus.QUEUED,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = createdAt,
            workspaceScopeId = cleanScope
        ).also(repository::save)
    }

    @Synchronized
    fun getTask(taskId: String): SpiderAgentTask? = repository.find(taskId)

    @Synchronized
    fun listTasks(): List<SpiderAgentTask> = repository.all()

    fun listRecentTasks(limit: Int = 30): List<SpiderAgentTask> = repository.recent(limit)

    fun taskCount(): Int = repository.count()

    @Synchronized
    fun beginPlanning(taskId: String): SpiderAgentTask = update(taskId) { task ->
        require(task.status == SpiderTaskStatus.QUEUED) { "Only queued tasks can start planning" }
        task.copy(status = SpiderTaskStatus.PLANNING, resumeStatus = null)
    }

    /** Invoke the injected planner port and fail the task safely if its proposal is invalid or unavailable. */
    @Synchronized
    fun planTask(taskId: String, planner: SpiderTaskPlanner): SpiderAgentTask {
        val planningTask = beginPlanning(taskId)
        return try {
            val proposal = planner.proposePlan(planningTask)
            attachPlan(taskId, proposal)
        } catch (_: Exception) {
            val current = repository.find(taskId)
            if (current?.status == SpiderTaskStatus.PLANNING) {
                update(taskId) { task ->
                    task.copy(
                        status = SpiderTaskStatus.FAILED,
                        lastErrorCode = "plan_failed",
                        resultSummary = "План не создан или не прошёл проверку; инструменты не запускались."
                    )
                }
            } else {
                throw IllegalStateException("Task could not enter safe planning failure state")
            }
        }
    }

    /** Apply a proposed plan but discard any planner-supplied status/result values. */
    @Synchronized
    fun attachPlan(taskId: String, proposedSteps: List<SpiderPlanStep>): SpiderAgentTask = update(taskId) { task ->
        require(task.status == SpiderTaskStatus.PLANNING) { "Task is not in planning state" }
        require(proposedSteps.isNotEmpty()) { "Plan must contain at least one step" }
        require(proposedSteps.size <= MAX_PLAN_STEPS) { "Plan contains too many steps" }
        val cleanSteps = proposedSteps.map { step ->
            require(step.id.isNotBlank() && step.id.length <= 120) { "Plan step ID is invalid" }
            require(step.title.isNotBlank() && step.title.length <= MAX_STEP_TITLE_LENGTH) { "Plan step title is invalid" }
            require(step.toolId != null) { "Plan contains a step without an executable typed tool; clarification flow is not implemented" }
            step.copy(status = SpiderStepStatus.PENDING, resultSummary = null)
        }
        require(cleanSteps.map { it.id }.toSet().size == cleanSteps.size) { "Plan step IDs must be unique" }
        task.copy(status = SpiderTaskStatus.RUNNING, steps = cleanSteps, lastErrorCode = null, resultSummary = null)
    }

    @Synchronized
    fun startStep(taskId: String, stepId: String): SpiderAgentTask = update(taskId) { task ->
        require(task.status == SpiderTaskStatus.RUNNING) { "Task is not running" }
        require(task.steps.none { it.status == SpiderStepStatus.RUNNING || it.status == SpiderStepStatus.WAITING_FOR_APPROVAL }) {
            "A task can execute only one step at a time"
        }
        val step = requireStep(task, stepId)
        require(step.status == SpiderStepStatus.PENDING) { "Only pending steps can start" }
        require(step.toolId != null) { "Step has no typed tool; clarification/planning is required" }
        task.copy(steps = replaceStep(task, step.copy(status = SpiderStepStatus.RUNNING)))
    }

    @Synchronized
    fun requireApproval(taskId: String, stepId: String): SpiderAgentTask = update(taskId) { task ->
        require(task.status == SpiderTaskStatus.RUNNING) { "Task is not running" }
        val step = requireStep(task, stepId)
        require(step.status == SpiderStepStatus.RUNNING) { "Only running steps can wait for approval" }
        task.copy(
            status = SpiderTaskStatus.WAITING_FOR_APPROVAL,
            steps = replaceStep(task, step.copy(status = SpiderStepStatus.WAITING_FOR_APPROVAL))
        )
    }

    @Synchronized
    fun resolveApproval(taskId: String, stepId: String, approved: Boolean): SpiderAgentTask = update(taskId) { task ->
        require(task.status == SpiderTaskStatus.WAITING_FOR_APPROVAL) { "Task is not waiting for approval" }
        val step = requireStep(task, stepId)
        require(step.status == SpiderStepStatus.WAITING_FOR_APPROVAL) { "Step is not waiting for approval" }
        if (approved) {
            // The executor must build/use a fresh one-time tool request after approval.
            task.copy(status = SpiderTaskStatus.RUNNING, steps = replaceStep(task, step.copy(status = SpiderStepStatus.PENDING)))
        } else {
            task.copy(
                status = SpiderTaskStatus.FAILED,
                steps = replaceStep(task, step.copy(status = SpiderStepStatus.SKIPPED, resultSummary = "Пользователь отклонил действие.")),
                lastErrorCode = "approval_denied",
                resultSummary = "Задача остановлена: действие не было подтверждено."
            )
        }
    }

    @Synchronized
    fun recordStepResult(
        taskId: String,
        stepId: String,
        succeeded: Boolean,
        summary: String,
        errorCode: String? = null
    ): SpiderAgentTask = update(taskId) { task ->
        require(task.status == SpiderTaskStatus.RUNNING) { "Task is not running" }
        val step = requireStep(task, stepId)
        require(step.status == SpiderStepStatus.RUNNING) { "Only running steps can complete" }
        val result = step.copy(
            status = if (succeeded) SpiderStepStatus.SUCCEEDED else SpiderStepStatus.FAILED,
            resultSummary = SpiderAuditRedactor.redact(summary).take(MAX_RESULT_SUMMARY_LENGTH)
        )
        val steps = replaceStep(task, result)
        when {
            !succeeded -> task.copy(
                status = SpiderTaskStatus.FAILED,
                steps = steps,
                lastErrorCode = errorCode?.take(80) ?: "step_failed",
                resultSummary = result.resultSummary
            )
            steps.all { it.status == SpiderStepStatus.SUCCEEDED } -> task.copy(
                status = SpiderTaskStatus.VERIFYING,
                steps = steps,
                lastErrorCode = null,
                resultSummary = "Все шаги выполнены; требуется независимая проверка результата."
            )
            steps.any { it.status == SpiderStepStatus.SKIPPED } -> task.copy(
                status = SpiderTaskStatus.FAILED,
                steps = steps,
                lastErrorCode = "step_skipped",
                resultSummary = "Не все шаги задачи выполнены."
            )
            else -> task.copy(status = SpiderTaskStatus.RUNNING, steps = steps, lastErrorCode = null)
        }
    }

    @Synchronized
    fun finishVerification(taskId: String, verified: Boolean, summary: String): SpiderAgentTask = update(taskId) { task ->
        require(task.status == SpiderTaskStatus.VERIFYING) { "Task is not awaiting verification" }
        val cleanSummary = SpiderAuditRedactor.redact(summary).take(MAX_RESULT_SUMMARY_LENGTH)
        task.copy(
            status = if (verified) SpiderTaskStatus.COMPLETED else SpiderTaskStatus.FAILED,
            resultSummary = cleanSummary,
            lastErrorCode = if (verified) null else "verification_failed"
        )
    }

    /**
     * Reconciles non-terminal states left by a process death. It deliberately never resumes an
     * in-flight tool automatically: the external side effect may have happened before the crash.
     */
    @Synchronized
    fun recoverInterruptedTasks(): List<SpiderAgentTask> {
        val recoverableStates = setOf(
            SpiderTaskStatus.PLANNING, SpiderTaskStatus.RUNNING,
            SpiderTaskStatus.WAITING_FOR_APPROVAL, SpiderTaskStatus.VERIFYING
        )
        val candidates = repository.recoverableForStartup().filter { task ->
            val interruptedState = if (task.status == SpiderTaskStatus.PAUSED) task.resumeStatus else task.status
            interruptedState in recoverableStates
        }
        return candidates.map { oldTask ->
            update(oldTask.id) { task ->
                val interruptedState = if (task.status == SpiderTaskStatus.PAUSED) task.resumeStatus else task.status
                val codeAndSummary = when (interruptedState) {
                    SpiderTaskStatus.PLANNING -> "planning_interrupted" to
                        "Планирование прервано перезапуском приложения. Автоматический повтор не запускался."
                    SpiderTaskStatus.RUNNING -> "execution_interrupted_unknown_outcome" to
                        "Приложение завершилось во время выполнения. Итог действия может быть неопределённым; перед повтором проверь состояние рабочего пространства."
                    SpiderTaskStatus.WAITING_FOR_APPROVAL -> "approval_expired_after_restart" to
                        "Ожидавшее подтверждение истекло после перезапуска. Действие не будет выполнено автоматически."
                    SpiderTaskStatus.VERIFYING -> "verification_interrupted" to
                        "Проверка результата прервана перезапуском. Задача не отмечена завершённой; проверь результат отдельно."
                    else -> "interrupted_after_restart" to "Задача прервана перезапуском приложения. Автоматический повтор не запускался."
                }
                val recoveredSteps = task.steps.map { step ->
                    when (step.status) {
                        SpiderStepStatus.RUNNING -> step.copy(
                            status = SpiderStepStatus.FAILED,
                            resultSummary = "Выполнение прервано; произошёл ли внешний эффект, автоматически определить нельзя."
                        )
                        SpiderStepStatus.WAITING_FOR_APPROVAL -> step.copy(
                            status = SpiderStepStatus.SKIPPED,
                            resultSummary = "Подтверждение истекло при перезапуске; действие не выполнялось автоматически."
                        )
                        else -> step
                    }
                }
                task.copy(
                    status = SpiderTaskStatus.FAILED,
                    steps = recoveredSteps,
                    lastErrorCode = codeAndSummary.first,
                    resumeStatus = null,
                    resultSummary = codeAndSummary.second.take(MAX_RESULT_SUMMARY_LENGTH)
                )
            }
        }
    }

    @Synchronized
    fun pause(taskId: String): SpiderAgentTask = update(taskId) { task ->
        require(task.status in ACTIVE_STATUSES) { "Only active tasks can be paused" }
        task.copy(status = SpiderTaskStatus.PAUSED, resumeStatus = task.status)
    }

    @Synchronized
    fun resume(taskId: String): SpiderAgentTask = update(taskId) { task ->
        require(task.status == SpiderTaskStatus.PAUSED) { "Task is not paused" }
        val restored = task.resumeStatus?.takeIf { it in ACTIVE_STATUSES }
            ?: if (task.steps.isEmpty()) SpiderTaskStatus.QUEUED else SpiderTaskStatus.RUNNING
        task.copy(status = restored, resumeStatus = null)
    }

    @Synchronized
    fun cancel(taskId: String): SpiderAgentTask = update(taskId) { task ->
        require(task.status !in TERMINAL_STATUSES) { "Terminal tasks cannot be cancelled again" }
        task.copy(
            status = SpiderTaskStatus.CANCELLED,
            resumeStatus = null,
            lastErrorCode = "cancelled",
            resultSummary = "Задача отменена пользователем."
        )
    }

    private fun update(taskId: String, transform: (SpiderAgentTask) -> SpiderAgentTask): SpiderAgentTask {
        val current = repository.find(taskId) ?: throw NoSuchElementException("Task not found")
        val changed = transform(current).copy(updatedAtEpochMs = clock())
        repository.save(changed)
        if (changed.status in TERMINAL_STATUSES && terminalTaskObserver != null) {
            try {
                terminalTaskObserver.invoke(changed)
                terminalObserverError = null
            } catch (_: Exception) {
                // Learning memory is secondary to the task ledger: do not roll back or misreport
                // the durable task transition because an optional learning write failed.
                terminalObserverError = "Learning memory capture failed; task history remains saved."
            }
        }
        return changed
    }

    private fun requireStep(task: SpiderAgentTask, stepId: String): SpiderPlanStep =
        task.steps.firstOrNull { it.id == stepId } ?: throw NoSuchElementException("Plan step not found")

    private fun replaceStep(task: SpiderAgentTask, replacement: SpiderPlanStep): List<SpiderPlanStep> =
        task.steps.map { if (it.id == replacement.id) replacement else it }

    companion object {
        const val MAX_GOAL_LENGTH = 4000
        const val MAX_PLAN_STEPS = 50
        const val MAX_STEP_TITLE_LENGTH = 200
        const val MAX_RESULT_SUMMARY_LENGTH = 400
        private val ACTIVE_STATUSES = setOf(
            SpiderTaskStatus.QUEUED, SpiderTaskStatus.PLANNING, SpiderTaskStatus.RUNNING,
            SpiderTaskStatus.WAITING_FOR_APPROVAL
        )
        private val TERMINAL_STATUSES = setOf(
            SpiderTaskStatus.COMPLETED, SpiderTaskStatus.FAILED, SpiderTaskStatus.CANCELLED
        )
    }
}

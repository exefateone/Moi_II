package com.iipauk.spider

/** Executor outcome; the policy decision alone is never treated as proof of execution. */
enum class SpiderToolCallStatus { WAITING_FOR_APPROVAL, DENIED, SUCCEEDED, FAILED }

data class SpiderToolHandlerResult(
    val succeeded: Boolean,
    val resultCode: String,
    val summary: String,
    /** Compact, typed verification evidence; never copied into the audit summary. */
    val verificationEvidence: String? = null,
    /** Bounded result for the user, not the audit log or the durable learning memory. */
    val outputPayload: String? = null
)

data class SpiderToolCallResult(
    val status: SpiderToolCallStatus,
    val disposition: SpiderPolicyDisposition,
    val risk: SpiderToolRisk,
    val resultCode: String,
    val message: String,
    val auditRecorded: Boolean,
    /** Opaque, size-bounded evidence for an independent verifier. Never displayed or audited verbatim. */
    val verificationEvidence: String? = null,
    /** Bounded, secret-redacted tool output. Never audited or inserted into episodic memory. */
    val outputPayload: String? = null
)

/** A handler supports exactly one typed tool ID; implementations must validate resource references themselves. */
interface SpiderToolHandler {
    val toolId: SpiderToolId
    val displayName: String
    fun execute(request: SpiderToolRequest): SpiderToolHandlerResult
}

/** Audit port keeps the executor unit-testable and allows the Android app-private logger to be injected. */
fun interface SpiderAuditSink {
    fun append(
        taskId: String,
        requestId: String,
        toolId: SpiderToolId,
        decision: SpiderPolicyDecision,
        resultCode: String,
        summary: String
    ): Boolean
}

/**
 * Fail-closed typed tool broker. It has no arbitrary command API, verifies policy before dispatch,
 * requires a registered handler, consumes request IDs once, and records a pre-execution audit event.
 */
class SpiderToolBroker(
    handlers: Collection<SpiderToolHandler>,
    private val auditSink: SpiderAuditSink,
    private val policyEngine: SpiderPolicyEngine = SpiderPolicyEngine(),
    private val requestLedger: SpiderRequestLedger = InMemorySpiderRequestLedger()
) {
    private val handlerById: Map<SpiderToolId, SpiderToolHandler>

    init {
        require(handlers.map { it.toolId }.toSet().size == handlers.size) { "Only one handler per tool ID may be registered" }
        handlerById = handlers.associateBy { it.toolId }
    }

    /** Runtime capability reporting reads the actual registered handler set instead of model claims. */
    fun isRegistered(toolId: SpiderToolId): Boolean = toolId in handlerById

    fun registeredToolIds(): Set<SpiderToolId> = handlerById.keys.toSet()

    @Synchronized
    fun execute(request: SpiderToolRequest, context: SpiderPolicyContext = SpiderPolicyContext()): SpiderToolCallResult {
        val decision = policyEngine.evaluate(request, context)
        if (decision.disposition == SpiderPolicyDisposition.DENY) {
            val recorded = tryAudit(request, decision, decision.reasonCode, decision.userMessage)
            return SpiderToolCallResult(
                SpiderToolCallStatus.DENIED, decision.disposition, decision.risk,
                decision.reasonCode, decision.userMessage, recorded
            )
        }

        // Never ask the user to approve an action that has no implementation to run.
        val handler = handlerById[request.toolId]
        if (handler == null) {
            val missing = SpiderPolicyDecision(
                SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "tool_handler_missing",
                "Для этого инструмента ещё не подключён исполнитель. Действие не выполнено."
            )
            val recorded = tryAudit(request, missing, missing.reasonCode, missing.userMessage)
            return SpiderToolCallResult(SpiderToolCallStatus.FAILED, missing.disposition, missing.risk, missing.reasonCode, missing.userMessage, recorded)
        }

        if (decision.disposition == SpiderPolicyDisposition.REQUIRE_APPROVAL) {
            val recorded = tryAudit(request, decision, decision.reasonCode, decision.userMessage)
            return SpiderToolCallResult(
                SpiderToolCallStatus.WAITING_FOR_APPROVAL, decision.disposition, decision.risk,
                decision.reasonCode, decision.userMessage, recorded
            )
        }

        // Persist the claim before the first side effect. The Android runtime injects a durable
        // ledger so a process restart cannot make an already attempted request executable again.
        when (runCatching { requestLedger.claim(request.requestId) }
            .getOrDefault(SpiderRequestClaimResult.STORAGE_UNAVAILABLE)) {
            SpiderRequestClaimResult.ALREADY_CLAIMED -> {
                val replay = SpiderPolicyDecision(
                    SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "duplicate_request_id",
                    "Этот запрос уже был использован. Создай новый запрос для следующего действия."
                )
                val recorded = tryAudit(request, replay, replay.reasonCode, replay.userMessage)
                return SpiderToolCallResult(SpiderToolCallStatus.DENIED, replay.disposition, replay.risk, replay.reasonCode, replay.userMessage, recorded)
            }
            SpiderRequestClaimResult.STORAGE_UNAVAILABLE -> {
                val unavailable = SpiderPolicyDecision(
                    SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "request_ledger_unavailable",
                    "Действие не запущено: невозможно безопасно зафиксировать одноразовый запрос."
                )
                val recorded = tryAudit(request, unavailable, unavailable.reasonCode, unavailable.userMessage)
                return SpiderToolCallResult(SpiderToolCallStatus.FAILED, unavailable.disposition, unavailable.risk, unavailable.reasonCode, unavailable.userMessage, recorded)
            }
            SpiderRequestClaimResult.CLAIMED -> Unit
        }

        val started = tryAudit(request, decision, "execution_started", "Запущен типизированный обработчик: ${handler.displayName}.")
        if (!started) {
            return SpiderToolCallResult(
                SpiderToolCallStatus.FAILED, SpiderPolicyDisposition.DENY, decision.risk,
                "audit_unavailable", "Действие не запущено: не удалось записать обязательное событие аудита.", false
            )
        }

        val outcome = try {
            handler.execute(request)
        } catch (_: Exception) {
            SpiderToolHandlerResult(false, "handler_exception", "Инструмент завершился внутренней ошибкой; подробности не переданы в журнал.")
        }
        val resultCode = SpiderAuditRedactor.redact(outcome.resultCode).take(80).ifBlank { "unspecified_result" }
        val summary = SpiderAuditRedactor.redact(outcome.summary).take(SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH)
        val finalAudit = tryAudit(request, decision, resultCode, summary)
        val succeeded = outcome.succeeded && finalAudit
        val evidence = outcome.verificationEvidence?.takeIf { it.length <= MAX_VERIFICATION_EVIDENCE_LENGTH }
        val rawOutput = outcome.outputPayload
        val output = when {
            !succeeded || rawOutput == null -> null
            rawOutput.length > MAX_TOOL_OUTPUT_LENGTH -> null
            else -> WorkspaceToolSafety.sanitizeOutput(rawOutput)
        }
        val outputTooLarge = succeeded && rawOutput != null && rawOutput.length > MAX_TOOL_OUTPUT_LENGTH
        return SpiderToolCallResult(
            status = if (succeeded && !outputTooLarge) SpiderToolCallStatus.SUCCEEDED else SpiderToolCallStatus.FAILED,
            disposition = decision.disposition,
            risk = decision.risk,
            resultCode = when {
                !finalAudit -> "audit_final_write_failed"
                outputTooLarge -> "tool_output_too_large"
                else -> resultCode
            },
            message = when {
                !finalAudit -> "${summary.ifBlank { "Действие завершилось." }} Журнал не подтвердил запись итогового события."
                outputTooLarge -> "Инструмент сформировал слишком большой вывод; он не был показан как полный и подтверждённый результат."
                else -> summary
            },
            auditRecorded = finalAudit,
            verificationEvidence = evidence.takeIf { succeeded && !outputTooLarge },
            outputPayload = output
        )
    }

    private fun tryAudit(
        request: SpiderToolRequest,
        decision: SpiderPolicyDecision,
        resultCode: String,
        summary: String
    ): Boolean {
        if (request.taskId.isBlank() || request.requestId.isBlank()) return false
        return runCatching {
            auditSink.append(
                request.taskId.take(120), request.requestId.take(120), request.toolId, decision,
                SpiderAuditRedactor.redact(resultCode).take(80),
                SpiderAuditRedactor.redact(summary).take(SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH)
            )
        }.getOrDefault(false)
    }

    private companion object {
        const val MAX_VERIFICATION_EVIDENCE_LENGTH = 256
        const val MAX_TOOL_OUTPUT_LENGTH = 64 * 1024
    }
}

internal object SpiderAuditRedactor {
    private val assignmentPattern = Regex("(?i)(api[_-]?key|access[_-]?token|password|secret|authorization)\\s*[:=]\\s*[^\\s,;]+")
    private val bearerPattern = Regex("(?i)Bearer\\s+[^\\s,;]+")

    // Redact Bearer credentials first; otherwise the authorization-assignment matcher could
    // replace only the word "Bearer" and accidentally leave the following credential in the log.
    fun redact(value: String): String = value
        .replace(bearerPattern, "Bearer [REDACTED]")
        .replace(assignmentPattern) { "${it.groupValues[1]}=[REDACTED]" }
}

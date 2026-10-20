package com.iipauk.spider

/**
 * Fail-closed policy gate for future agent tool calls.
 * The model cannot supply its own risk rating: risk and network needs are assigned here.
 */
class SpiderPolicyEngine {
    private data class Rule(
        val risk: SpiderToolRisk,
        val requiresNetwork: Boolean = false,
        val alwaysRequiresApproval: Boolean = false
    )

    private val rules = mapOf(
        SpiderToolId.SEARCH_PUBLIC_WEB to Rule(SpiderToolRisk.LOW, requiresNetwork = true),
        SpiderToolId.OPEN_BROWSER to Rule(SpiderToolRisk.LOW, requiresNetwork = true),
        SpiderToolId.LIST_WORKSPACE_FILES to Rule(SpiderToolRisk.LOW),
        SpiderToolId.SEARCH_WORKSPACE_CONTENT to Rule(SpiderToolRisk.GUARDED, alwaysRequiresApproval = true),
        SpiderToolId.READ_WORKSPACE_FILE to Rule(SpiderToolRisk.GUARDED, alwaysRequiresApproval = true),
        SpiderToolId.WRITE_WORKSPACE_FILE to Rule(SpiderToolRisk.GUARDED, alwaysRequiresApproval = true),
        // Tests can execute project code, so never treat them as harmless reads.
        SpiderToolId.RUN_PROJECT_TESTS to Rule(SpiderToolRisk.HIGH, alwaysRequiresApproval = true),
        // This is a read-only static analyzer; it never executes project code or shell commands.
        SpiderToolId.CHECK_WORKSPACE_PROJECT to Rule(SpiderToolRisk.GUARDED, alwaysRequiresApproval = true),
        SpiderToolId.READ_MEDIA_METADATA to Rule(SpiderToolRisk.LOW),
        SpiderToolId.PLAY_MEDIA to Rule(SpiderToolRisk.LOW),
        SpiderToolId.DOWNLOAD_REMOTE_FILE to Rule(SpiderToolRisk.GUARDED, requiresNetwork = true, alwaysRequiresApproval = true),
        SpiderToolId.INSTALL_PACKAGE to Rule(SpiderToolRisk.HIGH, requiresNetwork = true, alwaysRequiresApproval = true),
        SpiderToolId.DELETE_WORKSPACE_FILE to Rule(SpiderToolRisk.CRITICAL, alwaysRequiresApproval = true),
        SpiderToolId.SEND_EXTERNAL_CONTENT to Rule(SpiderToolRisk.CRITICAL, requiresNetwork = true, alwaysRequiresApproval = true)
    )

    fun evaluate(request: SpiderToolRequest, context: SpiderPolicyContext = SpiderPolicyContext()): SpiderPolicyDecision {
        if (request.requestId.isBlank() || request.requestId.length > 120 || request.taskId.isBlank()) {
            return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "invalid_request", "Запрос инструмента не прошёл проверку формата.")
        }
        if (request.summary.isBlank() || request.summary.length > 500) {
            return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "invalid_summary", "Нужно короткое описание действия перед его выполнением.")
        }
        val reference = request.resourceReference
        if (reference != null && (reference.length > 2048 || reference.any { it.code < 0x20 || it.code == 0x7f })) {
            return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "invalid_resource_reference", "Ссылка на ресурс не прошла проверку формата.")
        }
        if (request.workspaceId != null && (request.workspaceId.length > 120 || request.workspaceId.any { it.code < 0x20 || it.code == 0x7f })) {
            return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "invalid_workspace_reference", "Рабочее пространство не прошло проверку формата.")
        }
        if (request.inputText != null && (request.inputText.length > MAX_INPUT_CHARS || request.inputText.any { it == '\u0000' })) {
            return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "tool_input_too_large", "Ввод инструмента пусто/слишком велик или содержит запрещённый управляющий символ.")
        }
        if (request.toolId == SpiderToolId.WRITE_WORKSPACE_FILE && request.inputText == null) {
            return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "write_content_required", "Для записи файла необходимо передать текст после проверки и подтверждения пользователя.")
        }
        if (request.expectedPreimageSha256 != null &&
            request.expectedPreimageSha256 != "MISSING" &&
            !request.expectedPreimageSha256.matches(Regex("[a-f0-9]{64}"))) {
            return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "preimage_hash_invalid", "Контрольная сумма исходника не прошла проверку.")
        }
        if (request.expectedPreimageSha256 != null && request.toolId != SpiderToolId.WRITE_WORKSPACE_FILE) {
            return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "preimage_guard_not_supported", "Контрольная сумма исходника допустима только для записи файла.")
        }
        if (request.toolId == SpiderToolId.SEARCH_WORKSPACE_CONTENT && request.inputText.isNullOrBlank()) {
            return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "search_query_required", "Для поиска по проекту нужна поисковая фраза.")
        }
        val rule = rules[request.toolId]
            ?: return decision(SpiderPolicyDisposition.DENY, SpiderToolRisk.CRITICAL, "unknown_tool", "Инструмент отсутствует в разрешённом списке.")
        val approved = request.requestId in context.approvedRequestIds

        if (rule.requiresNetwork && !context.networkAccessEnabled && !approved) {
            return decision(SpiderPolicyDisposition.REQUIRE_APPROVAL, rule.risk, "network_permission_required", "Для этого шага нужен сетевой доступ. Разреши именно этот запрос или включи доступ в настройках.")
        }
        if (rule.alwaysRequiresApproval && !approved) {
            return decision(SpiderPolicyDisposition.REQUIRE_APPROVAL, rule.risk, "explicit_approval_required", "Перед выполнением нужно показать детали действия и получить отдельное подтверждение.")
        }
        return decision(SpiderPolicyDisposition.ALLOW, rule.risk, "policy_passed", "Проверка политики пройдена. Это разрешение само по себе не означает, что инструмент уже выполнен.")
    }

    private companion object { const val MAX_INPUT_CHARS = 65_536 }

    private fun decision(
        disposition: SpiderPolicyDisposition,
        risk: SpiderToolRisk,
        code: String,
        message: String
    ) = SpiderPolicyDecision(disposition, risk, code, message)
}

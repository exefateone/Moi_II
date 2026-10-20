package com.iipauk.spider

import org.json.JSONObject

/** One strict plan parser shared by local and remote models so neither can grant new tools. */
internal object SpiderPlanParser {
    private const val MAX_RESPONSE_CHARS = 24_000
    private const val MAX_PLAN_STEPS = 6
    private const val MAX_STEP_TITLE_LENGTH = 160
    private val currentlyImplementedTools = setOf(SpiderToolId.LIST_WORKSPACE_FILES, SpiderToolId.SEARCH_WORKSPACE_CONTENT, SpiderToolId.READ_WORKSPACE_FILE, SpiderToolId.WRITE_WORKSPACE_FILE, SpiderToolId.CHECK_WORKSPACE_PROJECT)

    fun parse(raw: String, expectedTaskId: String): List<SpiderPlanStep> {
        require(raw.length <= MAX_RESPONSE_CHARS) { "Ответ модели слишком большой." }
        val response = JSONObject(raw)
        val status = response.optString("status")
        require(response.optString("task_id") == expectedTaskId) { "Идентификатор задачи в ответе модели не совпал." }
        if (status == "unsupported") {
            val noSteps = response.optJSONArray("steps")?.length() == 0
            require(noSteps) { "Модель вернула неподдерживаемый статус вместе с действиями." }
            val explanation = response.optString("message", "").replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().take(180)
            throw IllegalArgumentException(explanation.ifBlank { "Для этой задачи пока нет подходящих разрешённых инструментов." })
        }
        require(status == "ok") { "Модель не подтвердила поддерживаемый план." }
        val steps = response.optJSONArray("steps") ?: throw IllegalArgumentException("В ответе отсутствуют шаги плана.")
        require(steps.length() in 1..MAX_PLAN_STEPS) { "Недопустимое количество шагов." }
        val result = ArrayList<SpiderPlanStep>(steps.length())
        val seen = mutableSetOf<String>()
        for (index in 0 until steps.length()) {
            val item = steps.optJSONObject(index) ?: throw IllegalArgumentException("Шаг плана повреждён.")
            val id = item.optString("id", "").trim()
            val title = item.optString("title", "").trim()
            val rawTool = item.optString("tool_id", "").trim()
            require(id.matches(Regex("[A-Za-z0-9._-]{1,80}")) && seen.add(id)) { "ID шага некорректен или повторяется." }
            require(title.isNotBlank() && title.length <= MAX_STEP_TITLE_LENGTH) { "Название шага недопустимо." }
            val toolId = runCatching { SpiderToolId.valueOf(rawTool) }.getOrNull()
                ?: throw IllegalArgumentException("План содержит неизвестный инструмент.")
            require(toolId in currentlyImplementedTools) { "План содержит инструмент, который ещё не реализован." }
            result += SpiderPlanStep(id = id, title = title, toolId = toolId)
        }
        return result
    }
}

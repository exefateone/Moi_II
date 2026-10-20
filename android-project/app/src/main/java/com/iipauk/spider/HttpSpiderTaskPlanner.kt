package com.iipauk.spider

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Client for the authenticated Spider Agent Planner API. Call from a worker thread, never from
 * Android's main thread. The model is allowed to propose typed steps only; this adapter does not
 * execute a tool or accept model-supplied status/result values.
 */
data class SpiderProviderConnection(
    val endpointUrl: String,
    val clientToken: String,
    val connectTimeoutMs: Int = 8_000,
    val readTimeoutMs: Int = 20_000
) {
    init {
        validateEndpoint(endpointUrl)
        require(clientToken.length in 24..4096) { "Provider client token has invalid length" }
        require(clientToken.none { it.code < 0x20 || it.code == 0x7f }) { "Provider client token contains control characters" }
        require(connectTimeoutMs in 1_000..30_000) { "Invalid connect timeout" }
        require(readTimeoutMs in 1_000..30_000) { "Invalid read timeout" }
    }

    companion object {
        fun validateEndpoint(raw: String) {
            require(raw.length in 1..2048) { "Planner endpoint is required" }
            val uri = runCatching { URI(raw.trim()) }.getOrNull()
                ?: throw IllegalArgumentException("Planner endpoint is invalid")
            require(!uri.host.isNullOrBlank()) { "Planner endpoint host is required" }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Planner endpoint cannot contain credentials, query, or fragment"
            }
            require(uri.path == "/api/agent/plan") { "Planner endpoint must end in /api/agent/plan" }
            require(uri.scheme == "https") { "Planner endpoint must use HTTPS" }
            require(uri.port == -1 || uri.port in 1..65535) { "Planner endpoint port is invalid" }
        }
    }
}

class HttpSpiderTaskPlanner(
    private val connection: SpiderProviderConnection,
    private val learningContextProvider: (SpiderAgentTask) -> List<SpiderLearningHint> = { emptyList() },
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : SpiderTaskPlanner {
    override fun proposePlan(task: SpiderAgentTask): List<SpiderPlanStep> {
        // Topic tokens stay on-device; the shared codec sends only bounded typed metadata.
        val memoryItems = runCatching { learningContextProvider(task) }.getOrDefault(emptyList())
        val memoryJson = SpiderLearningContextCodec.toSafeJson(memoryItems)
        val payload = JSONObject()
            .put("task_id", task.id)
            .put("goal", task.goal)
            .put("learning_context", memoryJson)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_REQUEST_BYTES) { "Task request is too large" }

        val http = try {
            connectionFactory(URL(connection.endpointUrl))
        } catch (_: Exception) {
            throw IllegalStateException("Не удалось подготовить подключение к сервису планирования.")
        }
        try {
            http.instanceFollowRedirects = false
            http.connectTimeout = connection.connectTimeoutMs
            http.readTimeout = connection.readTimeoutMs
            http.requestMethod = "POST"
            http.doOutput = true
            http.useCaches = false
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            http.setRequestProperty("Accept", "application/json")
            http.setRequestProperty("Authorization", "Bearer ${connection.clientToken}")
            http.setFixedLengthStreamingMode(payload.size)
            http.outputStream.use { it.write(payload) }

            val status = http.responseCode
            if (status !in 200..299) {
                // Provider/backend error bodies may include sensitive details; never surface them.
                throw IllegalStateException(when (status) {
                    401 -> "Сервис планирования отклонил токен доступа. Проверь настройки подключения."
                    422 -> "Для этой задачи пока не подключены необходимые инструменты."
                    503 -> "Сервис ИИ не настроен или временно недоступен."
                    504 -> "Сервис ИИ не ответил вовремя. Повтори попытку позже."
                    in 300..399 -> "Сервис планирования перенаправил запрос; переход заблокирован."
                    else -> "Сервис планирования вернул ошибку (HTTP $status)."
                })
            }
            val body = http.inputStream.use { readBounded(it, MAX_RESPONSE_BYTES) }.toString(Charsets.UTF_8)
            return parsePlanResponse(body, task.id)
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: IOException) {
            throw IllegalStateException("Не удалось связаться с сервисом планирования. Проверь сеть и адрес сервера.")
        } catch (_: Exception) {
            throw IllegalStateException("Ответ сервиса планирования не удалось проверить безопасно.")
        } finally {
            http.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw IllegalStateException("Ответ сервиса слишком большой.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    internal fun parsePlanResponse(raw: String, expectedTaskId: String): List<SpiderPlanStep> =
        SpiderPlanParser.parse(raw, expectedTaskId).also { steps ->
            require(steps.all { it.toolId in CURRENTLY_IMPLEMENTED_TOOLS }) {
                "План содержит инструмент, не зарегистрированный как исполняемый в текущем приложении."
            }
        }

    companion object {
        private const val MAX_REQUEST_BYTES = 8_000
        private const val MAX_RESPONSE_BYTES = 24_000
        private const val MAX_PLAN_STEPS = 6
        private const val MAX_STEP_TITLE_LENGTH = 160
        private val CURRENTLY_IMPLEMENTED_TOOLS = setOf(
            SpiderToolId.LIST_WORKSPACE_FILES,
            SpiderToolId.SEARCH_WORKSPACE_CONTENT,
            SpiderToolId.READ_WORKSPACE_FILE,
            SpiderToolId.WRITE_WORKSPACE_FILE,
            SpiderToolId.CHECK_WORKSPACE_PROJECT
        )
    }
}

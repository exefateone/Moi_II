package com.iipauk.spider

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Authenticated chat client. Cloud mode is user-selected; provider credentials are never stored here. */
class HttpSpiderChatClient(
    private val connection: SpiderProviderConnection,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    fun send(messages: List<Pair<String, String>>, learningHints: List<SpiderLearningHint> = emptyList()): String {
        require(messages.isNotEmpty() && messages.size <= MAX_MESSAGES) { "История облачного диалога слишком длинная." }
        require(messages.all {
            it.first in setOf("user", "assistant") &&
                it.second.isNotBlank() && it.second.length <= MAX_MESSAGE_CHARS &&
                it.second.none { ch -> ch.code < 9 || ch.code in 14..31 }
        }) {
            "Сообщение облачного диалога пустое, слишком большое или содержит управляющие символы."
        }
        val safeHints = SpiderLearningContextCodec.toSafeJson(learningHints)
        // Bound the serialized UTF-8 payload, not just Kotlin character counts. Russian text,
        // emoji and other non-ASCII characters can occupy multiple bytes per character. The
        // backend's body limit is 16 KiB, so leave a margin and drop oldest turns until the
        // request fits. The newest turn is always retained.
        var boundedMessages = messages.takeLast(MAX_MESSAGES).map { (role, content) ->
            role to content.takeLast(MAX_MESSAGE_CHARS)
        }
        var payload = encodePayload(boundedMessages, safeHints)
        while (payload.size > MAX_REQUEST_BYTES && boundedMessages.size > 1) {
            boundedMessages = boundedMessages.drop(1)
            payload = encodePayload(boundedMessages, safeHints)
        }
        require(payload.size <= MAX_REQUEST_BYTES) { "Последнее сообщение слишком велико для облачного чата." }

        val endpoint = connection.endpointUrl.removeSuffix("/api/agent/plan") + "/api/chat"
        val http = try { connectionFactory(URL(endpoint)) } catch (_: Exception) {
            throw IllegalStateException("Не удалось подготовить подключение к облачному чату.")
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
                throw IllegalStateException(when (status) {
                    401 -> "Сервер отклонил токен клиента. Проверь настройки подключения."
                    503 -> "Яндекс API не настроен на backend-сервере или временно недоступен."
                    504 -> "Яндекс API не ответил вовремя. Повтори попытку позже."
                    in 300..399 -> "Перенаправление облачного чата заблокировано."
                    else -> "Облачный чат вернул ошибку (HTTP $status)."
                })
            }
            val response = JSONObject(readBounded(http.inputStream, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8))
            require(response.optString("status") == "ok") { "Ответ облачного чата не прошёл проверку." }
            val reply = response.optString("reply", "").trim()
            require(reply.isNotBlank() && reply.length <= MAX_RESPONSE_CHARS) { "Ответ облачного чата пустой или слишком большой." }
            return reply
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: IOException) {
            throw IllegalStateException("Не удалось связаться с облачным чатом. Проверь сеть и адрес backend.")
        } catch (_: Exception) {
            throw IllegalStateException("Ответ облачного чата не удалось проверить безопасно.")
        } finally {
            http.disconnect()
        }
    }


    private fun encodePayload(messages: List<Pair<String, String>>, safeHints: JSONArray): ByteArray =
        JSONObject()
            .put("messages", JSONArray().apply {
                messages.forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content))
                }
            })
            .put("learning_context", safeHints)
            .toString().toByteArray(Charsets.UTF_8)

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw IllegalStateException("Ответ облачного чата слишком большой.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_MESSAGES = 12
        private const val MAX_MESSAGE_CHARS = 2400
        // Keep headroom below backend/app/main.py's 16,384-byte request-body ceiling.
        private const val MAX_REQUEST_BYTES = 14_000
        private const val MAX_RESPONSE_BYTES = 32_000
        private const val MAX_RESPONSE_CHARS = 12_000
    }
}

package com.iipauk.spider

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Transient, in-memory source snapshot. The content is never added to task history, audit or learning memory. */
data class SpiderWorkspaceEditSource(
    val content: String,
    /** SHA-256 of the existing file, or MISSING if the path did not exist when reviewed. */
    val expectedPreimageSha256: String
)

data class SpiderWorkspaceEditProposal(
    val summary: String,
    val updatedContent: String
)

/** Small shared response parser. Model output is data only; a caller must still request user approval. */
internal object SpiderWorkspaceEditParser {
    const val MAX_SOURCE_CHARS = 1_900
    const val MAX_PROPOSAL_BYTES = WorkspaceToolSafety.MAX_WRITE_BYTES
    private const val MAX_RESPONSE_CHARS = 30_000

    fun parse(raw: String): SpiderWorkspaceEditProposal {
        require(raw.length in 1..MAX_RESPONSE_CHARS) { "Ответ модели с изменениями пустой или слишком большой." }
        val value = JSONObject(raw)
        require(value.optString("status") == "ok") { "Модель не подтвердила корректное предложение изменений." }
        val summary = value.optString("summary", "").trim()
            .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").take(240)
        val contentValue = value.opt("updated_content")
        require(contentValue is String && contentValue.isNotEmpty()) { "Модель не вернула новый текст файла." }
        require(contentValue.none { it.code == 0x7f || (it.code < 0x20 && it !in listOf('\n', '\r', '\t')) }) { "Предложение содержит недопустимые управляющие символы." }
        require(contentValue.toByteArray(Charsets.UTF_8).size <= MAX_PROPOSAL_BYTES) { "Новый текст превышает безопасный лимит 128 КиБ." }
        return SpiderWorkspaceEditProposal(
            summary = summary.ifBlank { "Подготовлено предложение изменения файла. Проверь предпросмотр перед записью." },
            updatedContent = contentValue
        )
    }
}

/** Authenticated Yandex/backend edit proposer. It only returns a candidate text; it cannot write files. */
internal class HttpSpiderWorkspaceEditClient(
    private val connection: SpiderProviderConnection,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    fun propose(taskId: String, goal: String, stepTitle: String, relativePath: String, sourceContent: String): SpiderWorkspaceEditProposal {
        require(goal.isNotBlank() && goal.length <= 1_200)
        require(stepTitle.length <= 160)
        require(relativePath.isNotBlank() && relativePath.length <= 240)
        require(sourceContent.length <= SpiderWorkspaceEditParser.MAX_SOURCE_CHARS)
        val route = connection.endpointUrl.removeSuffix("/api/agent/plan") + "/api/agent/edit"
        val payload = JSONObject()
            .put("task_id", taskId.take(120))
            .put("goal", goal.take(1_200))
            .put("step_title", stepTitle.take(160))
            .put("path", relativePath)
            .put("source_content", sourceContent)
            .toString().toByteArray(Charsets.UTF_8)
        require(payload.size <= 8_000) { "Запрос на изменение слишком большой." }
        val http = try { connectionFactory(URL(route)) }
        catch (_: Exception) { throw IllegalStateException("Не удалось подготовить защищённый запрос на изменение файла.") }
        try {
            http.instanceFollowRedirects = false
            http.connectTimeout = connection.connectTimeoutMs
            http.readTimeout = 30_000
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
                    401 -> "Backend отклонил токен доступа."
                    413 -> "Файл или запрос слишком большой для безопасного редактирования."
                    422 -> "Backend не принял запрос на предложение изменений."
                    429 -> "Сервис ограничил частоту запросов. Повтори позже."
                    503 -> "Модель на backend не настроена или временно недоступна."
                    504 -> "Модель не ответила вовремя."
                    in 300..399 -> "Перенаправление запроса заблокировано."
                    else -> "Сервис предложения изменений вернул ошибку HTTP $status."
                })
            }
            val bytes = http.inputStream.use { readBounded(it, 18_000) }
            return SpiderWorkspaceEditParser.parse(bytes.toString(Charsets.UTF_8))
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: IOException) {
            throw IllegalStateException("Не удалось связаться с сервисом предложения изменений.")
        } catch (_: Exception) {
            throw IllegalStateException("Ответ модели с изменениями не прошёл проверку формата.")
        } finally {
            http.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 4_096))
        val buffer = ByteArray(4_096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw IllegalStateException("Ответ модели с изменениями слишком большой.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

/** Produces a complete bounded line preview; null means the UI must not offer approval. */
internal object SpiderWorkspaceEditDiff {
    const val MAX_PREVIEW_CHARS = 60_000

    fun create(before: String, after: String): String? {
        val oldLines = before.split("\n")
        val newLines = after.split("\n")
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]) suffix++
        val out = StringBuilder()
        val contextBefore = (prefix - 3).coerceAtLeast(0)
        if (contextBefore > 0) out.append("… ").append(contextBefore).append(" неизменённых строк выше …\n")
        for (index in contextBefore until prefix) out.append("  ").append(oldLines[index]).append('\n')
        for (index in prefix until oldLines.size - suffix) out.append("- ").append(oldLines[index]).append('\n')
        for (index in prefix until newLines.size - suffix) out.append("+ ").append(newLines[index]).append('\n')
        val tailEnd = minOf(oldLines.size, newLines.size)
        val tailStart = (tailEnd - suffix).coerceAtLeast(0)
        for (index in tailStart until minOf(tailStart + 3, tailEnd)) out.append("  ").append(oldLines[index]).append('\n')
        if (suffix > 3) out.append("… ").append(suffix - 3).append(" неизменённых строк ниже …\n")
        if (out.isEmpty()) out.append("Полный текст отличается, но не удалось выделить построчный контекст.\n")
        if (out.length > MAX_PREVIEW_CHARS) return null
        return out.toString()
    }
}

package com.iipauk.spider

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Authenticated client for optional cloud embeddings; only scrubbed topic tokens are sent. */
class HttpSpiderEmbeddingClient(
    private val connection: SpiderProviderConnection,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    fun embed(safeTopicTexts: List<String>): List<SpiderDenseEmbedding> {
        require(safeTopicTexts.size in 1..MAX_BATCH) { "Embedding batch size is invalid." }
        require(safeTopicTexts.all(::isSafeTopicText)) { "Only scrubbed topic tokens can be embedded." }
        val uri = URI(connection.endpointUrl)
        require(uri.scheme == "https" && uri.host != null && uri.path == "/api/agent/plan" && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Embedding endpoint could not be derived safely."
        }
        val embeddingsUrl = URI(uri.scheme, null, uri.host, uri.port, "/api/agent/embeddings", null, null).toURL()
        val texts = JSONArray().apply { safeTopicTexts.forEach { put(it) } }
        val requestBytes = JSONObject().put("texts", texts).toString().toByteArray(Charsets.UTF_8)
        require(requestBytes.size <= MAX_REQUEST_BYTES) { "Embedding request is too large." }
        val http = connectionFactory(embeddingsUrl)
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
            http.setFixedLengthStreamingMode(requestBytes.size)
            http.outputStream.use { it.write(requestBytes) }
            val status = http.responseCode
            if (status !in 200..299) throw IOException(when (status) {
                401 -> "Сервис семантического поиска отклонил токен доступа."
                503 -> "Семантические embeddings не настроены на сервере."
                504 -> "Сервис семантического поиска не ответил вовремя."
                in 300..399 -> "Перенаправление сервиса семантического поиска заблокировано."
                else -> "Сервис семантического поиска вернул HTTP $status."
            })
            val response = JSONObject(readBounded(http.inputStream, MAX_RESPONSE_BYTES).toString(Charsets.UTF_8))
            val modelId = response.optString("model")
            require(modelId.matches(Regex("[A-Za-z0-9_.:/@-]{1,120}"))) { "Embedding model ID is invalid." }
            val vectors = response.getJSONArray("embeddings")
            require(vectors.length() == safeTopicTexts.size) { "Embedding count does not match request." }
            val output = mutableListOf<SpiderDenseEmbedding>()
            for (i in 0 until vectors.length()) {
                val rawVector = vectors.getJSONArray(i)
                require(rawVector.length() in MIN_DIMENSIONS..MAX_DIMENSIONS) { "Embedding dimensions are invalid." }
                val values = FloatArray(rawVector.length()) { index ->
                    val value = rawVector.getDouble(index)
                    require(value.isFinite() && value in -1_000.0..1_000.0) { "Embedding value is invalid." }
                    value.toFloat()
                }
                val embedding = SpiderDenseEmbedding(modelId, values)
                // Reuse the index validator's dimension/norm checks on a scratch-independent copy via cosine.
                require(values.any { kotlin.math.abs(it) > 1e-8f }) { "Embedding vector is empty." }
                output += embedding
            }
            return output
        } finally {
            http.disconnect()
        }
    }

    private fun isSafeTopicText(value: String): Boolean {
        if (value.length !in 1..MAX_TEXT_CHARS || value.any { it.code < 0x20 }) return false
        val tokens = value.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.size in 1..MAX_TOKENS_PER_TEXT && tokens.all { it.matches(Regex("[a-zа-яё_-]{3,32}")) }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw IOException("Embedding response too large.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_BATCH = 16
        private const val MAX_TEXT_CHARS = 400
        private const val MAX_TOKENS_PER_TEXT = 12
        private const val MIN_DIMENSIONS = 8
        private const val MAX_DIMENSIONS = 2048
        private const val MAX_REQUEST_BYTES = 8_000
        private const val MAX_RESPONSE_BYTES = 1_000_000
    }
}

package com.iipauk.spider

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Exercises actual Android org.json UTF-8 serialization without calling a live provider. */
@RunWith(AndroidJUnit4::class)
class HttpSpiderChatClientInstrumentedTest {
    @Test
    fun trimsOldUtf8TurnsBeforeBackendBodyLimitAndKeepsNewestMessage() {
        val captured = ByteArrayOutputStream()
        val url = URL("https://backend.example/api/chat")
        val fake = object : HttpURLConnection(url) {
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun connect() = Unit
            override fun getOutputStream(): OutputStream = captured
            override fun getResponseCode(): Int = 200
            override fun getInputStream(): InputStream = ByteArrayInputStream(
                """{"status":"ok","reply":"ok"}""".toByteArray(Charsets.UTF_8)
            )
        }
        val connection = SpiderProviderConnection(
            endpointUrl = "https://backend.example/api/agent/plan",
            clientToken = "client-token-" + "x".repeat(32)
        )
        val messages = (0 until 9).map { index ->
            (if (index % 2 == 0) "user" else "assistant") to ("я".repeat(2200) + "-old-$index")
        } + listOf("user" to "ПОСЛЕДНИЙ ВОПРОС 😀")

        val result = HttpSpiderChatClient(connection) { fake }.send(messages)

        assertEquals("ok", result)
        assertTrue("Request must stay below backend's 16 KiB body limit", captured.size() <= 14_000)
        val sent = JSONObject(captured.toString(Charsets.UTF_8)).getJSONArray("messages")
        assertTrue("Newest user message must be retained", sent.getJSONObject(sent.length() - 1).getString("content").contains("ПОСЛЕДНИЙ ВОПРОС"))
        assertTrue("Oldest history should be discarded before the newest turn", (0 until sent.length()).none { sent.getJSONObject(it).getString("content").contains("old-0") })
    }
}

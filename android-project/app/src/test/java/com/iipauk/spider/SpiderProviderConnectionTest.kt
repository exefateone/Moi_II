package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpiderProviderConnectionTest {
    @Test
    fun acceptsHttpsPlannerEndpointAndReasonableTimeouts() {
        val connection = SpiderProviderConnection(
            endpointUrl = "https://agent.example.com/api/agent/plan",
            clientToken = "test-client-token-with-at-least-24-chars",
            connectTimeoutMs = 5_000,
            readTimeoutMs = 12_000
        )
        assertEquals("https://agent.example.com/api/agent/plan", connection.endpointUrl)
    }

    @Test
    fun rejectsHttpEndpointAndUnexpectedPath() {
        assertThrows(IllegalArgumentException::class.java) {
            SpiderProviderConnection("http://agent.example.com/api/agent/plan", "test-client-token-with-at-least-24-chars")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpiderProviderConnection("https://agent.example.com/chat/completions", "test-client-token-with-at-least-24-chars")
        }
    }

    @Test
    fun rejectsEndpointCredentialsQueryAndFragment() {
        val token = "test-client-token-with-at-least-24-chars"
        listOf(
            "https://user:password@agent.example.com/api/agent/plan",
            "https://agent.example.com/api/agent/plan?token=secret",
            "https://agent.example.com/api/agent/plan#fragment"
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                SpiderProviderConnection(endpoint, token)
            }
        }
    }

    @Test
    fun rejectsShortClientTokenAndOutOfRangeTimeouts() {
        assertThrows(IllegalArgumentException::class.java) {
            SpiderProviderConnection("https://agent.example.com/api/agent/plan", "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpiderProviderConnection("https://agent.example.com/api/agent/plan", "test-client-token-with-at-least-24-chars", readTimeoutMs = 100_000)
        }
    }
}

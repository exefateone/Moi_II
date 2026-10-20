package com.iipauk.spider

import org.junit.Assert.*
import org.junit.Test

class SafeUrlPolicyTest {
    @Test fun acceptsHttpAndHttpsIncludingPorts() {
        assertNotNull(SafeUrlPolicy.parse("https://example.com/path?q=1"))
        assertNotNull(SafeUrlPolicy.parse("http://example.com/"))
        assertNotNull(SafeUrlPolicy.parse("https://example.com:8443/path"))
    }

    @Test fun rejectsNonWebSchemes() {
        listOf("file:///etc/passwd", "content://provider/item", "javascript:alert(1)", "data:text/html,x", "intent://host/#Intent;end", "ftp://example.com").forEach {
            assertNull("should reject $it", SafeUrlPolicy.parse(it))
        }
    }

    @Test fun rejectsEmbeddedCredentials() {
        assertNull(SafeUrlPolicy.parse("https://user:password@example.com"))
        assertNull(SafeUrlPolicy.parse("https://user@example.com"))
    }

    @Test fun addressDefaultsToHttps() {
        assertEquals("https", SafeUrlPolicy.normaliseAddress("example.com")?.scheme)
    }

    @Test fun rejectsAmbiguousAndMalformedAuthorities() {
        listOf(
            "https://example.com:0/", "https://example.com:65536/", "https://example.com:abc/",
            "https://example.com:/", "https://exa mple.com/", "https://example.com\\@evil.test/",
            "https:///missing-host", "https://[::1"
        ).forEach { assertNull("should reject $it", SafeUrlPolicy.parse(it)) }
    }

    @Test fun addressBarTurnsPlainTextIntoSearchButRejectsSpecialSchemes() {
        val search = SafeUrlPolicy.addressOrSearch("weather in New York")
        assertNotNull(search)
        assertEquals("duckduckgo.com", search?.host)
        assertTrue(search?.getQueryParameter("q") == "weather in New York")
        assertEquals("example.com", SafeUrlPolicy.addressOrSearch("example.com")?.host)
        assertNull(SafeUrlPolicy.addressOrSearch("javascript:alert(1)"))
        assertNull(SafeUrlPolicy.addressOrSearch("file:///etc/passwd"))
    }

    @Test fun rejectsControlCharactersAndExcessiveLength() {
        assertNull(SafeUrlPolicy.parse("https://example.com/\nheader-injection"))
        assertNull(SafeUrlPolicy.normaliseAddress("x".repeat(8193)))
    }
}

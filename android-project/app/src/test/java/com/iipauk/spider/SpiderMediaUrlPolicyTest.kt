package com.iipauk.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpiderMediaUrlPolicyTest {
    @Test fun acceptsHttpsProgressiveMediaUrl() {
        val parsed = SpiderMediaUrlPolicy.parseHttpsUrl("https://cdn.example.test/film.mp4?signature=private")
        assertNotNull(parsed)
        assertEquals("https", parsed?.scheme)
        assertEquals("film.mp4", parsed?.path?.substringAfterLast('/'))
        assertFalse(SpiderMediaUrlPolicy.displayTitle(parsed!!).contains("private"))
    }

    @Test fun addsHttpsToBareHost() {
        assertEquals("https://media.example.test/movie.mp4", SpiderMediaUrlPolicy.parseHttpsUrl("media.example.test/movie.mp4")?.toASCIIString())
    }

    @Test fun detectsHlsAndDashFromPathNotQuery() {
        val hls = SpiderMediaUrlPolicy.parseHttpsUrl("https://cdn.example.test/live.m3u8?token=x")!!
        val dash = SpiderMediaUrlPolicy.parseHttpsUrl("https://cdn.example.test/manifest.MPD?token=y")!!
        assertEquals("application/x-mpegURL", SpiderMediaUrlPolicy.inferMimeType(hls))
        assertEquals("application/dash+xml", SpiderMediaUrlPolicy.inferMimeType(dash))
        assertNull(SpiderMediaUrlPolicy.inferMimeType(SpiderMediaUrlPolicy.parseHttpsUrl("https://cdn.example.test/watch?id=4")!!))
    }

    @Test fun rejectsPlainHttp() {
        assertNull(SpiderMediaUrlPolicy.parseHttpsUrl("http://media.example.test/movie.mp4"))
    }

    @Test fun rejectsCredentialsInUrl() {
        assertNull(SpiderMediaUrlPolicy.parseHttpsUrl("https://user:password@media.example.test/movie.mp4"))
    }

    @Test fun rejectsDangerousSchemes() {
        assertNull(SpiderMediaUrlPolicy.parseHttpsUrl("javascript:alert(1)"))
        assertNull(SpiderMediaUrlPolicy.parseHttpsUrl("file:///sdcard/movie.mp4"))
        assertNull(SpiderMediaUrlPolicy.parseHttpsUrl("content://provider/item/1"))
    }

    @Test fun rejectsAmbiguousAndOversizedInput() {
        assertNull(SpiderMediaUrlPolicy.parseHttpsUrl("https://media.example.test\\@evil.test/movie.mp4"))
        assertNull(SpiderMediaUrlPolicy.parseHttpsUrl("https://media.example.test:99999/movie.mp4"))
        assertNull(SpiderMediaUrlPolicy.parseHttpsUrl("https://media.example.test/" + "x".repeat(9000)))
        assertNull(SpiderMediaUrlPolicy.parseHttpsUrl("https://media.example.test/\nmovie.mp4"))
    }

    @Test fun displayTitleDoesNotExposeSignedQuery() {
        val parsed = SpiderMediaUrlPolicy.parseHttpsUrl("https://cdn.example.test/archive/movie.m3u8?access_token=secret-value")!!
        val title = SpiderMediaUrlPolicy.displayTitle(parsed)
        assertEquals("movie.m3u8", title)
        assertFalse(title.contains("secret-value"))
    }
}

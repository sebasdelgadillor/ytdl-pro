package com.deniscerri.ytdl

import com.deniscerri.ytdl.util.DomainUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainUtilTest {

    @Test
    fun normalizeRootDomain_handlesSubdomainsShortlinksAndPublicSuffixes() {
        assertEquals("instagram.com", DomainUtil.normalizeRootDomain("https://www.instagram.com/reel/abc123/"))
        assertEquals("instagram.com", DomainUtil.normalizeRootDomain("https://instagr.am/p/xyz"))
        assertEquals("youtube.com", DomainUtil.normalizeRootDomain("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("youtube.com", DomainUtil.normalizeRootDomain("m.youtube.com"))
        assertEquals("facebook.com", DomainUtil.normalizeRootDomain("https://fb.watch/12345/"))
        assertEquals("tiktok.com", DomainUtil.normalizeRootDomain("https://vm.tiktok.com/ZM123/"))
        assertEquals("x.com", DomainUtil.normalizeRootDomain("https://mobile.twitter.com/user/status/1"))
        assertEquals("bbc.co.uk", DomainUtil.normalizeRootDomain("https://www.bbc.co.uk/news"))
        assertEquals("globo.com.br", DomainUtil.normalizeRootDomain("https://video.globo.com.br/watch"))
        assertNull(DomainUtil.normalizeRootDomain(""))
        assertNull(DomainUtil.normalizeRootDomain("localhost"))
        assertNull(DomainUtil.normalizeRootDomain("http://192.168.1.10/test"))
        assertNull(DomainUtil.normalizeRootDomain("not a domain"))
    }

    @Test
    fun relatedCookieDomains_expandsKnownPlatformPairs() {
        assertEquals(listOf("x.com", "twitter.com"), DomainUtil.relatedCookieDomains("x.com"))
        assertEquals(listOf("x.com", "twitter.com"), DomainUtil.relatedCookieDomains("twitter.com"))
        assertEquals(listOf("youtube.com", "google.com"), DomainUtil.relatedCookieDomains("youtube.com"))
        assertEquals(listOf("instagram.com", "meta.com"), DomainUtil.relatedCookieDomains("instagram.com"))
        assertEquals(listOf("facebook.com", "meta.com"), DomainUtil.relatedCookieDomains("facebook.com"))
    }

    @Test
    fun chromiumExpiryToUnixSeconds_converts1601MicrosAndPreservesUnix() {
        assertEquals(0L, DomainUtil.chromiumExpiryToUnixSeconds(0L))
        assertEquals(0L, DomainUtil.chromiumExpiryToUnixSeconds(-5L))
        assertEquals(1700000000L, DomainUtil.chromiumExpiryToUnixSeconds(1700000000L))
        // 2023-11-14T22:13:20Z in Chromium microseconds since 1601-01-01
        val chromiumMicros = (1700000000L + 11_644_473_600L) * 1_000_000L
        assertEquals(1700000000L, DomainUtil.chromiumExpiryToUnixSeconds(chromiumMicros))
    }

    @Test
    fun formatNetscapeCookieLine_producesValidSevenFieldLines() {
        val httpOnlyLine = DomainUtil.formatNetscapeCookieLine(
            domain = "instagram.com",
            includeSubdomains = true,
            path = "/",
            secure = true,
            expiryUnixSeconds = 1800000000L,
            name = "sessionid",
            value = "abc123",
            httpOnly = true
        )
        assertEquals(
            "#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t1800000000\tsessionid\tabc123",
            httpOnlyLine
        )
        assertEquals(7, httpOnlyLine.split("\t").size)

        val emptyValueLine = DomainUtil.formatNetscapeCookieLine(
            domain = ".example.com",
            includeSubdomains = false,
            path = "",
            secure = false,
            expiryUnixSeconds = 0L,
            name = "flag",
            value = "",
            httpOnly = false
        )
        assertEquals(".example.com\tFALSE\t/\tFALSE\t0\tflag\t", emptyValueLine)
        assertEquals(7, emptyValueLine.split("\t").size)
    }

    @Test
    fun isLikelyAuthCookie_distinguishesAuthAndTrackingCookies() {
        assertTrue(DomainUtil.isLikelyAuthCookie("sessionid", "instagram.com"))
        assertTrue(DomainUtil.isLikelyAuthCookie("c_user", "facebook.com"))
        assertTrue(DomainUtil.isLikelyAuthCookie("auth_token", "x.com"))
        assertTrue(DomainUtil.isLikelyAuthCookie("SAPISID", "youtube.com"))
        assertTrue(DomainUtil.isLikelyAuthCookie("custom_user_session_v2", "example.com"))
        assertFalse(DomainUtil.isLikelyAuthCookie("_ga", "instagram.com"))
        assertFalse(DomainUtil.isLikelyAuthCookie("theme", "youtube.com"))
        assertFalse(DomainUtil.isLikelyAuthCookie("lang", "x.com"))
    }
}

package com.deniscerri.ytdl

import com.deniscerri.ytdl.util.PlatformRateLimiter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlatformRateLimiterTest {

    @Before
    fun setUp() {
        PlatformRateLimiter.resetForTests()
    }

    @Test
    fun policyForUrl_differentiatesStrictSocialStandardVideoAndDefaultDomains() {
        val ig = PlatformRateLimiter.policyForUrl("https://www.instagram.com/reel/abc123/")
        val x = PlatformRateLimiter.policyForUrl("https://mobile.twitter.com/user/status/1")
        val yt = PlatformRateLimiter.policyForUrl("https://youtu.be/jNQXAC9IVRw")
        val other = PlatformRateLimiter.policyForUrl("https://cdn.example.org/video.mp4")

        assertTrue(ig.minIntervalMs >= 2_500L)
        assertEquals("1.5", ig.ytdlpSleepRequestsSec)
        assertEquals(ig, x)

        assertEquals(1_000L, yt.minIntervalMs)
        assertEquals("1", yt.ytdlpSleepRequestsSec)

        assertEquals(500L, other.minIntervalMs)
        assertEquals("", other.ytdlpSleepRequestsSec)
        assertEquals("3", other.maxRetries)
    }

    @Test
    fun firstRequestHasZeroDelay_andSubsequentRapidRequestsWaitCooldown() = runBlocking {
        assertEquals(0L, PlatformRateLimiter.computeWaitMillis("instagram.com", nowMs = 10_000L, jitterFraction = 0.0))

        val firstWait = PlatformRateLimiter.awaitTurnForUrl("https://www.instagram.com/reel/abc123/")
        assertEquals(0L, firstWait)

        val rapidFollowup = PlatformRateLimiter.computeWaitMillis(
            domain = "instagram.com",
            nowMs = System.currentTimeMillis(),
            jitterFraction = 0.0
        )
        assertTrue("Expected > 2000ms cooldown, was $rapidFollowup", rapidFollowup in 2_000L..2_600L)

        // After enough time passes (> 5000ms), cooldown drops to 0
        val afterLongPause = PlatformRateLimiter.computeWaitMillis(
            domain = "instagram.com",
            nowMs = System.currentTimeMillis() + 10_000L,
            jitterFraction = 1.0
        )
        assertEquals(0L, afterLongPause)
    }

    @Test
    fun adaptiveBackoffAndRateLimitErrorDetection_scaleOn429Responses() {
        assertTrue(PlatformRateLimiter.isRateLimitError("HTTP Error 429: Too Many Requests"))
        assertTrue(PlatformRateLimiter.isRateLimitError("Instagram API: Please wait a few minutes before you try again"))
        assertTrue(PlatformRateLimiter.isRateLimitError("Rate limit exceeded, try again later"))
        assertFalse(PlatformRateLimiter.isRateLimitError("HTTP Error 404: Not Found"))
        assertFalse(PlatformRateLimiter.isRateLimitError(null))

        val now = 100_000L
        val backoff1 = PlatformRateLimiter.recordRateLimitForUrl("https://www.instagram.com/p/1", nowMs = now)
        assertEquals(15_000L, backoff1)

        val waitRightAfter = PlatformRateLimiter.computeWaitMillis("instagram.com", nowMs = now + 2_000L, jitterFraction = 0.0)
        assertEquals(13_000L, waitRightAfter)

        val backoff2 = PlatformRateLimiter.recordRateLimitForUrl("https://www.instagram.com/p/2", nowMs = now + 5_000L)
        assertEquals(30_000L, backoff2)

        val backoff3 = PlatformRateLimiter.recordRateLimitForUrl("https://www.instagram.com/p/3", nowMs = now + 10_000L)
        assertEquals(45_000L, backoff3)
    }
}

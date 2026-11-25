package com.deniscerri.ytdl

import com.deniscerri.ytdl.util.MediaHistoryMatcher
import com.deniscerri.ytdl.util.MediaHistoryMatcher.MediaDownloadState
import com.deniscerri.ytdl.util.MediaHistoryMatcher.MediaHistoryIndex
import com.deniscerri.ytdl.util.MediaHistoryMatcher.MediaTypeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaHistoryMatcherTest {

    @Test
    fun extractCanonicalMediaKeys_unifiesYouTubeVariantsAndPreservesCase() {
        val standard = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&si=abc123"
        val shortLink = "https://youtu.be/dQw4w9WgXcQ?si=xyz"
        val mobileShorts = "https://m.youtube.com/shorts/dQw4w9WgXcQ?feature=share"
        val music = "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD1"

        assertEquals("yt:dQw4w9WgXcQ", MediaHistoryMatcher.primaryMediaKey(standard))
        assertEquals("yt:dQw4w9WgXcQ", MediaHistoryMatcher.primaryMediaKey(shortLink))
        assertEquals("yt:dQw4w9WgXcQ", MediaHistoryMatcher.primaryMediaKey(mobileShorts))
        assertEquals("yt:dQw4w9WgXcQ", MediaHistoryMatcher.primaryMediaKey(music))

        // Different case video ID must remain distinct!
        assertNotEquals(
            MediaHistoryMatcher.primaryMediaKey("https://youtu.be/dQw4w9WgXcQ"),
            MediaHistoryMatcher.primaryMediaKey("https://youtu.be/dqw4w9wgxcq")
        )
    }

    @Test
    fun extractCanonicalMediaKeys_unifiesInstagramTikTokFacebookXAndReddit() {
        assertEquals(
            MediaHistoryMatcher.primaryMediaKey("https://www.instagram.com/reel/CxYz123Ab/?igsh=foo"),
            MediaHistoryMatcher.primaryMediaKey("https://www.instagram.com/somecreator/reel/CxYz123Ab/comments/")
        )
        assertEquals("ig:CxYz123Ab", MediaHistoryMatcher.primaryMediaKey("https://www.instagram.com/p/CxYz123Ab/"))

        assertEquals(
            "tt:7351234567890123456",
            MediaHistoryMatcher.primaryMediaKey("https://www.tiktok.com/@creator/video/7351234567890123456?is_from_webapp=1")
        )
        assertEquals(
            "fb:987654321012345",
            MediaHistoryMatcher.primaryMediaKey("https://m.facebook.com/watch/?v=987654321012345&ref=sharing")
        )
        assertEquals(
            "fb:987654321012345",
            MediaHistoryMatcher.primaryMediaKey("https://www.facebook.com/reel/987654321012345/")
        )
        assertEquals(
            "x:1812345678901234567",
            MediaHistoryMatcher.primaryMediaKey("https://x.com/user/status/1812345678901234567?s=20")
        )
        assertEquals(
            "rd:1abc2de",
            MediaHistoryMatcher.primaryMediaKey("https://old.reddit.com/r/videos/comments/1abc2de/sample_post/")
        )
    }

    @Test
    fun mediaHistoryIndex_enforcesPrecedenceAndFiltersMatchedKeys() {
        val index = MediaHistoryIndex(
            downloadedUrls = listOf(
                "https://www.youtube.com/watch?v=doneVideo01&si=old",
                "https://www.youtube.com/watch?v=overlapVid2",
                "https://www.instagram.com/p/ImgDone99/"
            ),
            inProgressUrls = listOf(
                "https://youtu.be/overlapVid2",
                "https://m.facebook.com/reel/123456789012/"
            )
        )

        assertEquals(MediaDownloadState.DOWNLOADED, index.getState("https://youtu.be/doneVideo01"))
        assertEquals(MediaDownloadState.DOWNLOADED, index.getState("https://www.instagram.com/creator/reel/ImgDone99/"))
        // Active/queued takes precedence over history if both exist
        assertEquals(MediaDownloadState.IN_PROGRESS, index.getState("https://www.youtube.com/watch?v=overlapVid2"))
        assertEquals(MediaDownloadState.IN_PROGRESS, index.getState("https://www.facebook.com/reel/123456789012/"))
        assertEquals(MediaDownloadState.NEW, index.getState("https://www.youtube.com/watch?v=brandNew99"))

        val (dlKeys, progKeys) = index.matchedKeysFor(
            listOf(
                "https://m.youtube.com/watch?v=doneVideo01",
                "https://www.youtube.com/watch?v=brandNew99",
                "https://www.facebook.com/reel/123456789012/"
            )
        )
        assertTrue("yt:doneVideo01" in dlKeys)
        assertFalse("ig:ImgDone99" in dlKeys) // not on this page
        assertTrue("fb:123456789012" in progKeys)
        assertFalse("yt:overlapVid2" in progKeys)
    }

    @Test
    fun matchesBatchFilters_filtersByVideoImageAndOnlyNewState() {
        assertTrue(MediaHistoryMatcher.matchesBatchFilters(true, MediaDownloadState.NEW, MediaTypeFilter.ALL, false))
        assertTrue(MediaHistoryMatcher.matchesBatchFilters(false, MediaDownloadState.DOWNLOADED, MediaTypeFilter.ALL, false))

        assertTrue(MediaHistoryMatcher.matchesBatchFilters(true, MediaDownloadState.NEW, MediaTypeFilter.VIDEOS, true))
        assertFalse(MediaHistoryMatcher.matchesBatchFilters(false, MediaDownloadState.NEW, MediaTypeFilter.VIDEOS, true))
        assertFalse(MediaHistoryMatcher.matchesBatchFilters(true, MediaDownloadState.DOWNLOADED, MediaTypeFilter.VIDEOS, true))

        assertTrue(MediaHistoryMatcher.matchesBatchFilters(false, MediaDownloadState.NEW, MediaTypeFilter.IMAGES, true))
        assertTrue(MediaHistoryMatcher.matchesBatchFilters(false, MediaDownloadState.IN_PROGRESS, MediaTypeFilter.IMAGES, false))
        assertFalse(MediaHistoryMatcher.matchesBatchFilters(false, MediaDownloadState.IN_PROGRESS, MediaTypeFilter.IMAGES, true))
        assertFalse(MediaHistoryMatcher.matchesBatchFilters(true, MediaDownloadState.NEW, MediaTypeFilter.IMAGES, false))
    }
}

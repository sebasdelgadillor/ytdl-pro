package com.deniscerri.ytdl

import com.deniscerri.ytdl.util.BrowserMediaUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMediaUtilTest {

    @Test
    fun `extractChromiumVersion parses full and major version`() {
        val raw = "Mozilla/5.0 (Linux; Android 16; SM-S918B; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/158.0.8127.51 Mobile Safari/537.36"
        val parsed = BrowserMediaUtil.extractChromiumVersion(raw)
        assertEquals("158.0.8127.51", parsed.fullVersion)
        assertEquals("158", parsed.majorVersion)

        val mobileUa = BrowserMediaUtil.buildMobileChromeUserAgent(raw)
        assertFalse(mobileUa.contains("; wv"))
        assertFalse(mobileUa.contains("Version/4.0"))
        assertTrue(mobileUa.contains("Chrome/158.0.8127.51 Mobile Safari/537.36"))

        val desktopUa = BrowserMediaUtil.buildDesktopChromeUserAgent(parsed.fullVersion)
        assertTrue(desktopUa.contains("Windows NT 10.0"))
        assertTrue(desktopUa.contains("Chrome/158.0.8127.51 Safari/537.36"))
    }

    @Test
    fun `isLikelyContentImageUrl accepts social CDN and direct images while filtering UI noise`() {
        assertTrue(BrowserMediaUtil.isLikelyContentImageUrl("https://scontent.fbog2-4.fna.fbcdn.net/v/t39.30808-6/468123456_n.jpg?_nc_cat=101"))
        assertTrue(BrowserMediaUtil.isLikelyContentImageUrl("https://scontent.cdninstagram.com/v/t51.2885-15/467111_n.webp?efg=1"))
        assertTrue(BrowserMediaUtil.isLikelyContentImageUrl("https://pbs.twimg.com/media/Gbcd123?format=jpg&name=large"))
        assertTrue(BrowserMediaUtil.isLikelyContentImageUrl("data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD"))

        assertFalse(BrowserMediaUtil.isLikelyContentImageUrl("https://static.xx.fbcdn.net/rsrc.php/v4/yB/r/icon.png"))
        assertFalse(BrowserMediaUtil.isLikelyContentImageUrl("https://scontent.fbcdn.net/v/t1.0-1/p50x50/avatar.jpg"))
        assertFalse(BrowserMediaUtil.isLikelyContentImageUrl("https://scontent.cdninstagram.com/v/t51.2885-19/44123456_n.jpg"))
        assertFalse(BrowserMediaUtil.isLikelyContentImageUrl("https://scontent.cdninstagram.com/v/t51.2885-15/s150x150/avatar.jpg"))
        assertFalse(BrowserMediaUtil.isLikelyContentImageUrl("https://scontent.fbcdn.net/v/t39.30808-1/p100x100/pic.jpg"))
        assertFalse(BrowserMediaUtil.isLikelyContentImageUrl("https://www.facebook.com/favicon.ico"))
        assertFalse(BrowserMediaUtil.isLikelyContentImageUrl("https://video.fbcdn.net/v/t42.1790-2/sample.mp4"))
        assertFalse(BrowserMediaUtil.isLikelyContentImageUrl("data:image/svg+xml;base64,PHN2Zz4="))
    }

    @Test
    fun `extractBestSrcFromSrcset picks highest width even when URLs contain commas`() {
        val srcset = "https://scontent.fbcdn.net/v/t39.30808-6/photo,w_480.jpg 480w, " +
            "https://scontent.fbcdn.net/v/t39.30808-6/photo,w_1440.jpg 1440w, " +
            "https://scontent.fbcdn.net/v/t39.30808-6/photo,w_720.jpg 720w"
        val best = BrowserMediaUtil.extractBestSrcFromSrcset(srcset)
        assertEquals("https://scontent.fbcdn.net/v/t39.30808-6/photo,w_1440.jpg", best)
        assertNull(BrowserMediaUtil.extractBestSrcFromSrcset("   "))
    }

    @Test
    fun `scoreImageCandidate prefers large content photo over small avatar`() {
        val fullScore = BrowserMediaUtil.scoreImageCandidate(
            url = "https://scontent.fbcdn.net/v/t39.30808-6/12345_n.jpg",
            width = 1080,
            height = 1350,
            source = "point_dom"
        )
        val tinyScore = BrowserMediaUtil.scoreImageCandidate(
            url = "https://scontent.fbcdn.net/v/avatar_s.jpg",
            width = 48,
            height = 48,
            source = "dom"
        )
        assertTrue(fullScore > tinyScore + 2000)
    }

    @Test
    fun `parseNetscapeCookieText handles HttpOnly and skips expired or comments`() {
        val sample = """
            # Netscape HTTP Cookie File
            # Comment line
            .instagram.com	TRUE	/	TRUE	1900000000	csrftoken	abc123
            #HttpOnly_.instagram.com	TRUE	/	TRUE	1900000000	sessionid	secret_val
            .instagram.com	TRUE	/	TRUE	1000000000	expired_tok	old
            invalid_line_without_tabs
        """.trimIndent()

        val parsed = BrowserMediaUtil.parseNetscapeCookieText(sample, nowSeconds = 1800000000L)
        assertEquals(2, parsed.size)
        assertEquals("csrftoken", parsed[0].name)
        assertFalse(parsed[0].httpOnly)
        val session = parsed.find { it.name == "sessionid" }
        assertNotNull(session)
        assertTrue(session!!.httpOnly)
        assertEquals(".instagram.com", session.domain)
    }

    @Test
    fun `isDirectStaticImageFile distinguishes CDN image assets from HTML post URLs`() {
        assertTrue(BrowserMediaUtil.isDirectStaticImageFile("https://scontent.fclo1-3.fna.fbcdn.net/v/t39.30808-6/123_n.jpg?_nc_cat=1"))
        assertTrue(BrowserMediaUtil.isDirectStaticImageFile("https://pbs.twimg.com/media/Gbcd123?format=jpg&name=large"))
        assertTrue(BrowserMediaUtil.isDirectStaticImageFile("data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD"))

        assertFalse(BrowserMediaUtil.isDirectStaticImageFile("https://m.facebook.com/photo/?fbid=123456&set=a.789"))
        assertFalse(BrowserMediaUtil.isDirectStaticImageFile("https://www.instagram.com/p/C12345/"))
        assertFalse(BrowserMediaUtil.isDirectStaticImageFile("https://m.facebook.com/reel/1541253761169421/"))
        assertFalse(BrowserMediaUtil.isDirectStaticImageFile("https://m.facebook.com/user/posts/999888/"))
    }

    @Test
    fun `isLikelyVideoPostUrl detects general video endpoints`() {
        assertTrue(BrowserMediaUtil.isLikelyVideoPostUrl("https://m.facebook.com/reel/1541253761169421/"))
        assertTrue(BrowserMediaUtil.isLikelyVideoPostUrl("https://www.instagram.com/reel/C9xyz123/"))
        assertTrue(BrowserMediaUtil.isLikelyVideoPostUrl("https://m.facebook.com/watch/?v=998877"))
        assertTrue(BrowserMediaUtil.isLikelyVideoPostUrl("https://www.instagram.com/reels/C_xyz/"))
        assertTrue(BrowserMediaUtil.isLikelyVideoPostUrl("https://www.youtube.com/shorts/abc123"))
        assertTrue(BrowserMediaUtil.isLikelyVideoPostUrl("https://example.com/media/clip.mp4?token=1"))

        assertFalse(BrowserMediaUtil.isLikelyVideoPostUrl("https://m.facebook.com/photo/?fbid=123"))
        assertFalse(BrowserMediaUtil.isLikelyVideoPostUrl("https://m.facebook.com/"))
    }

    @Test
    fun `scorePermalinkCandidate prioritizes media and post permalinks over nav and noise`() {
        val reelScore = BrowserMediaUtil.scorePermalinkCandidate("https://m.facebook.com/reel/123456/")
        val postScore = BrowserMediaUtil.scorePermalinkCandidate("https://m.facebook.com/somepage/posts/98765/")
        val profileScore = BrowserMediaUtil.scorePermalinkCandidate("https://m.facebook.com/somepage", isDirectWrapper = true)
        val homeScore = BrowserMediaUtil.scorePermalinkCandidate("https://m.facebook.com/")
        val sharerScore = BrowserMediaUtil.scorePermalinkCandidate("https://m.facebook.com/sharer.php?u=x")

        assertTrue(reelScore > postScore)
        assertTrue(postScore > profileScore)
        assertTrue(profileScore > 0)
        assertTrue(homeScore < 0)
        assertTrue(sharerScore < 0)
        assertTrue(BrowserMediaUtil.isContentPermalinkUrl("https://www.instagram.com/p/ABC123/"))
        assertFalse(BrowserMediaUtil.isContentPermalinkUrl("https://m.facebook.com/login/"))
    }

    @Test
    fun `hasVideoPlayerHint matches multilingual player labels and duration badges`() {
        assertTrue(BrowserMediaUtil.hasVideoPlayerHint("Reproductor de video"))
        assertTrue(BrowserMediaUtil.hasVideoPlayerHint("Play video"))
        assertTrue(BrowserMediaUtil.hasVideoPlayerHint("Toca para activar sonido"))
        assertTrue(BrowserMediaUtil.hasVideoPlayerHint("0:45"))

        assertFalse(BrowserMediaUtil.hasVideoPlayerHint("Puede ser una imagen de loris perezoso"))
        assertFalse(BrowserMediaUtil.hasVideoPlayerHint("Photo by Alice"))
    }

    @Test
    fun `rejects reels audio links and bare feed listings while accepting real permalinks`() {
        val audioAttrUrl = "https://www.instagram.com/reels/audio/29033540703000015/"
        assertTrue(BrowserMediaUtil.scorePermalinkCandidate(audioAttrUrl) < 0)
        assertFalse(BrowserMediaUtil.isContentPermalinkUrl(audioAttrUrl))
        assertFalse(BrowserMediaUtil.isLikelyVideoPostUrl(audioAttrUrl))

        listOf(
            "https://www.instagram.com/reels/",
            "https://www.facebook.com/watch/",
            "https://www.youtube.com/shorts/"
        ).forEach { listing ->
            assertTrue("Expected feed/listing: $listing", BrowserMediaUtil.isFeedOrListingUrl(listing))
            assertTrue("Expected negative permalink score: $listing", BrowserMediaUtil.scorePermalinkCandidate(listing) < 0)
            assertFalse("Expected non-permalink: $listing", BrowserMediaUtil.isContentPermalinkUrl(listing))
            assertFalse("Expected non-video post: $listing", BrowserMediaUtil.isLikelyVideoPostUrl(listing))
        }

        val fbWatchVideo = "https://www.facebook.com/watch/?v=123"
        assertFalse(BrowserMediaUtil.isFeedOrListingUrl(fbWatchVideo))
        assertTrue(BrowserMediaUtil.isContentPermalinkUrl(fbWatchVideo))
        assertTrue(BrowserMediaUtil.isLikelyVideoPostUrl(fbWatchVideo))

        val igReel = "https://www.instagram.com/reels/DgDlyZlSfzv/"
        assertFalse(BrowserMediaUtil.isFeedOrListingUrl(igReel))
        assertTrue(BrowserMediaUtil.isContentPermalinkUrl(igReel))
        assertTrue(BrowserMediaUtil.isLikelyVideoPostUrl(igReel))
    }

    @Test
    fun `canonicalizePermalinkUrl strips liked_by and comments subpages`() {
        assertEquals(
            "https://www.instagram.com/p/DgJAZdeDVw0/",
            BrowserMediaUtil.canonicalizePermalinkUrl("https://www.instagram.com/p/DgJAZdeDVw0/liked_by/")
        )
        assertEquals(
            "https://www.instagram.com/reel/ABC123/",
            BrowserMediaUtil.canonicalizePermalinkUrl("https://www.instagram.com/reel/ABC123/comments/")
        )
    }

    @Test
    fun `isGenericPlaceholderTitle identifies player chrome and empty descriptions`() {
        assertTrue(BrowserMediaUtil.isGenericPlaceholderTitle("Video player"))
        assertTrue(BrowserMediaUtil.isGenericPlaceholderTitle("Reproductor de video"))
        assertTrue(BrowserMediaUtil.isGenericPlaceholderTitle("Instagram"))
        assertTrue(BrowserMediaUtil.isGenericPlaceholderTitle("Facebook"))
        assertTrue(BrowserMediaUtil.isGenericPlaceholderTitle("No hay ninguna descripción de la foto disponible."))
        assertTrue(BrowserMediaUtil.isGenericPlaceholderTitle("Foto del perfil de nailah5572"))

        assertFalse(BrowserMediaUtil.isGenericPlaceholderTitle("Video by supongamos3d"))
    }

    @Test
    fun `isGenericSiteImageUrl filters logos favicons and icons while keeping real photos`() {
        assertTrue(BrowserMediaUtil.isGenericSiteImageUrl(""))
        assertTrue(BrowserMediaUtil.isGenericSiteImageUrl("https://www.instagram.com/favicon.ico"))
        assertTrue(BrowserMediaUtil.isGenericSiteImageUrl("https://static.cdninstagram.com/rsrc.php/v3/yx/r/N9J1.png"))
        assertTrue(BrowserMediaUtil.isGenericSiteImageUrl("https://example.com/assets/logo-dark.png"))
        assertTrue(BrowserMediaUtil.isGenericSiteImageUrl("https://example.com/vector.svg"))

        assertFalse(BrowserMediaUtil.isGenericSiteImageUrl("https://picsum.photos/id/237/800/600.jpg"))
        assertFalse(BrowserMediaUtil.isGenericSiteImageUrl("https://scontent-mia3-1.cdninstagram.com/v/t51.2885-15/45678_n.jpg"))
    }

    @Test
    fun `buildImageOnlyResultItem and predicates classify image audio and clean titles`() {
        val imgItem = BrowserMediaUtil.buildImageOnlyResultItem(
            url = "https://example.com/gallery/sunset_view.webp?v=2",
            imageUrl = "https://example.com/gallery/sunset_view.webp?v=2",
            title = "Instagram"
        )
        assertTrue(BrowserMediaUtil.isImageOnlyResult(imgItem))
        assertFalse(BrowserMediaUtil.isAudioOnlyResult(imgItem))
        assertEquals("webp", imgItem.formats.first().container)
        assertEquals("https://example.com/gallery/sunset_view.webp?v=2", BrowserMediaUtil.resolveBestImageUrl(imgItem))
        assertEquals("sunset_view", imgItem.title)

        val audioItem = imgItem.copy(
            url = "https://example.com/track",
            formats = arrayListOf(
                com.deniscerri.ytdl.database.models.Format(
                    format_id = "251",
                    container = "webm",
                    vcodec = "none",
                    acodec = "opus",
                    format_note = "128kbps audio"
                )
            )
        )
        assertFalse(BrowserMediaUtil.isImageOnlyResult(audioItem))
        assertTrue(BrowserMediaUtil.isAudioOnlyResult(audioItem))

        val videoItem = imgItem.copy(
            url = "https://example.com/watch?v=123",
            formats = arrayListOf(
                com.deniscerri.ytdl.database.models.Format(
                    format_id = "22",
                    container = "mp4",
                    vcodec = "avc1",
                    acodec = "mp4a",
                    format_note = "720p"
                )
            )
        )
        assertFalse(BrowserMediaUtil.isImageOnlyResult(videoItem))
        assertFalse(BrowserMediaUtil.isAudioOnlyResult(videoItem))

        assertEquals("jpg", BrowserMediaUtil.inferImageExtensionFromUrl("https://pbs.twimg.com/media/AbCd?format=jpg&name=large"))
        assertEquals("png", BrowserMediaUtil.inferImageExtensionFromUrl("https://cdn.example.com/pic", "image/png; charset=utf-8"))
    }
}

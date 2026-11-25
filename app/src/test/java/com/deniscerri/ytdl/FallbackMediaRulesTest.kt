package com.deniscerri.ytdl

import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.AudioPreferences
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.VideoPreferences
import com.deniscerri.ytdl.util.extractors.fallback.FallbackMediaRules
import com.deniscerri.ytdl.util.extractors.fallback.FallbackMediaRules.MediaCandidate
import com.deniscerri.ytdl.util.extractors.fallback.FallbackMediaRules.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackMediaRulesTest {

    private fun sampleDownloadItem(
        type: DownloadType = DownloadType.video,
        format: Format = Format(),
        container: String = "mp4",
        bitrate: String = ""
    ) = DownloadItem(
        id = 42L,
        url = "https://example.com/watch?v=1",
        title = "Sample: Test / Video?",
        author = "Creator",
        thumb = "",
        duration = "2:00",
        type = type,
        format = format,
        container = container,
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "",
        website = "example.com",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(bitrate = bitrate),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = "Queued",
        downloadStartTime = 0L,
        logID = null
    )

    @Test
    fun classifyMediaCandidate_acceptsStreamsAndRejectsNonMediaAdsAndSegments() {
        val mp4 = FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/v/reel_1080p.mp4?token=1")
        assertNotNull(mp4)
        assertEquals(StreamKind.PROGRESSIVE_MP4, mp4!!.kind)
        assertEquals(1080, mp4.height)

        val hls = FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/master.m3u8")
        assertEquals(StreamKind.HLS_MANIFEST, hls?.kind)

        val dash = FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/manifest.mpd")
        assertEquals(StreamKind.DASH_MANIFEST, dash?.kind)

        val webm = FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/clip_720p.webm")
        assertEquals(StreamKind.PROGRESSIVE_WEBM, webm?.kind)
        assertEquals(720, webm?.height)

        val m4a = FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/track.m4a")
        assertEquals(StreamKind.AUDIO_ONLY, m4a?.kind)

        val mp3 = FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/song.mp3")
        assertEquals(StreamKind.AUDIO_ONLY, mp3?.kind)

        val extensionlessMime = FallbackMediaRules.classifyMediaCandidate(
            url = "https://cdn.example.com/playback?id=abc",
            mimeType = "video/mp4; codecs=\"avc1\""
        )
        assertEquals(StreamKind.PROGRESSIVE_MP4, extensionlessMime?.kind)

        // Rejected schemes, static assets, trackers, and segments
        assertNull(FallbackMediaRules.classifyMediaCandidate("blob:https://example.com/uuid-1"))
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/thumb.png"))
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/poster.jpg"))
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/app.js"))
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/style.css"))
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://googleads.g.doubleclick.net/video.mp4"))
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/segment01.ts"))
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/seg-1.m4s"))
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/init.mp4"))
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/video.mp4?range=0-100"))
    }

    @Test
    fun extractMediaFromHtml_extractsMetaVideoDomSourceAndUnescapedJson() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="Test &amp; Demo Clip" />
                <meta property="og:image" content="https://cdn.example.com/cover.jpg" />
                <meta property="og:video:secure_url" content="https://cdn.example.com/meta_720p.mp4" />
              </head>
              <body>
                <video controls><source src="/media/dom_480p.mp4" type="video/mp4"></video>
                <script>
                  window.__DATA__ = {"playable_url_quality_hd":"https:\/\/cdn.example.com\/v.mp4\u0026token=1"};
                </script>
              </body>
            </html>
        """.trimIndent()

        val result = FallbackMediaRules.extractMediaFromHtml(html, "https://www.example.com/post/123")
        assertEquals("Test & Demo Clip", result.title)
        assertEquals("https://cdn.example.com/cover.jpg", result.thumbnail)

        val urls = result.candidates.map { it.url }
        assertTrue(urls.contains("https://cdn.example.com/meta_720p.mp4"))
        assertTrue(urls.contains("https://www.example.com/media/dom_480p.mp4"))
        assertTrue(urls.contains("https://cdn.example.com/v.mp4&token=1"))

        val hdJsonCandidate = result.candidates.first { it.url == "https://cdn.example.com/v.mp4&token=1" }
        assertTrue(hdJsonCandidate.height >= 720)
    }

    @Test
    fun preferenceParsers_readTargetResolutionBitrateAndContainers() {
        val itemGeneric720 = sampleDownloadItem(
            type = DownloadType.video,
            format = Format(format_id = "720p_ytdlnisgeneric"),
            container = "Default"
        )
        assertEquals(720, FallbackMediaRules.parseRequestedMaxHeight(itemGeneric720))
        assertEquals("mp4", FallbackMediaRules.resolveTargetVideoContainer(itemGeneric720))

        val itemPortrait1080 = sampleDownloadItem(
            type = DownloadType.video,
            format = Format(format_id = "137", _width = "1080", _height = "1920"),
            container = "webm"
        )
        assertEquals(1080, FallbackMediaRules.parseRequestedMaxHeight(itemPortrait1080))
        assertEquals("webm", FallbackMediaRules.resolveTargetVideoContainer(itemPortrait1080))

        val itemAudio = sampleDownloadItem(
            type = DownloadType.audio,
            container = "Default",
            bitrate = "320k"
        )
        assertEquals(320, FallbackMediaRules.parseRequestedAudioBitrateKbps(itemAudio))
        assertEquals("mp3", FallbackMediaRules.resolveTargetAudioContainer(itemAudio))

        val itemAudioDefault = sampleDownloadItem(type = DownloadType.audio, container = "m4a", bitrate = "")
        assertEquals(192, FallbackMediaRules.parseRequestedAudioBitrateKbps(itemAudioDefault))
        assertEquals("m4a", FallbackMediaRules.resolveTargetAudioContainer(itemAudioDefault))
    }

    @Test
    fun selectBestForDownload_handlesResolutionCapVideoOnlyPairingMuxedAndAudioFallback() {
        val vid1080Muxed = MediaCandidate(
            url = "https://cdn.example.com/1080.mp4",
            kind = StreamKind.PROGRESSIVE_MP4,
            height = 1080
        )
        val vid720Muxed = MediaCandidate(
            url = "https://cdn.example.com/720.mp4",
            kind = StreamKind.PROGRESSIVE_MP4,
            height = 720
        )
        val vid480Muxed = MediaCandidate(
            url = "https://cdn.example.com/480.mp4",
            kind = StreamKind.PROGRESSIVE_MP4,
            height = 480
        )
        val capped720 = FallbackMediaRules.selectBestForDownload(
            candidates = listOf(vid1080Muxed, vid480Muxed, vid720Muxed),
            type = DownloadType.video,
            targetMaxHeight = 720
        )
        assertEquals(vid720Muxed.url, capped720.primaryVideo?.url)
        assertNull(capped720.pairedAudio)

        // Video-only 1080p pairs with best M4A audio when available
        val vid1080Only = MediaCandidate(
            url = "https://cdn.example.com/1080_video.mp4",
            kind = StreamKind.PROGRESSIVE_MP4,
            height = 1080,
            isVideoOnly = true,
            hasAudio = false
        )
        val m4aAudio = MediaCandidate(
            url = "https://cdn.example.com/audio.m4a",
            kind = StreamKind.AUDIO_ONLY,
            bitrateKbps = 128,
            mimeType = "audio/mp4"
        )
        val pairedSelection = FallbackMediaRules.selectBestForDownload(
            candidates = listOf(vid720Muxed, vid1080Only, m4aAudio),
            type = DownloadType.video,
            targetMaxHeight = 1080
        )
        assertEquals(vid1080Only.url, pairedSelection.primaryVideo?.url)
        assertEquals(m4aAudio.url, pairedSelection.pairedAudio?.url)

        // When NO audio is available, muxed 720p beats silent video-only 1080p
        val muxedWhenNoAudio = FallbackMediaRules.selectBestForDownload(
            candidates = listOf(vid720Muxed, vid1080Only),
            type = DownloadType.video,
            targetMaxHeight = 1080
        )
        assertEquals(vid720Muxed.url, muxedWhenNoAudio.primaryVideo?.url)
        assertNull(muxedWhenNoAudio.pairedAudio)

        // Audio mode picks audio candidate first, else falls back to video/HLS for extraction
        val audioDirect = FallbackMediaRules.selectBestForDownload(
            candidates = listOf(vid1080Muxed, m4aAudio),
            type = DownloadType.audio
        )
        assertEquals(m4aAudio.url, audioDirect.audioOnly?.url)

        val hls = MediaCandidate(
            url = "https://cdn.example.com/master.m3u8",
            kind = StreamKind.HLS_MANIFEST
        )
        val audioFromHls = FallbackMediaRules.selectBestForDownload(
            candidates = listOf(hls),
            type = DownloadType.audio
        )
        assertEquals(hls.url, audioFromHls.audioOnly?.url)
    }

    @Test
    fun ffmpegBuildersAndSanitizeFilename_generateExpectedCommands() {
        assertEquals("Sample_ Test _ Video_", FallbackMediaRules.sanitizeFilename("Sample: Test / Video?", 42L))
        assertEquals("media_42", FallbackMediaRules.sanitizeFilename("   ", 42L))

        val headerArg = FallbackMediaRules.buildFfmpegHeaderArg(
            mapOf(
                "Cookie" to "sessionid=abc",
                "Referer" to "https://instagram.com/\r\n",
                "X-Custom-Ignored" to "foo"
            )
        )
        assertEquals("Cookie: sessionid=abc\r\nReferer: https://instagram.com/\r\n", headerArg)

        val muxArgs = FallbackMediaRules.buildMuxVideoAudioArgs("v.tmp", "a.tmp", "out.mp4", audioCodecCopy = true)
        assertTrue(muxArgs.contains("-c:v") && muxArgs.contains("copy") && muxArgs.contains("aac") .not())
        assertTrue(muxArgs.contains("+faststart"))

        val hlsVideoArgs = FallbackMediaRules.buildHlsOrDashDownloadArgs(
            manifestUrl = "https://cdn.example.com/master.m3u8",
            headers = mapOf("Cookie" to "a=b"),
            outputPath = "out.mp4",
            isAudioOnly = false,
            audioContainer = "m4a",
            audioBitrateKbps = 192
        )
        assertTrue(hlsVideoArgs.contains("aac_adtstoasc"))
        assertTrue(hlsVideoArgs.contains("-headers"))

        val dashVideoArgs = FallbackMediaRules.buildHlsOrDashDownloadArgs(
            manifestUrl = "https://cdn.example.com/manifest.mpd",
            headers = emptyMap(),
            outputPath = "out.mp4",
            isAudioOnly = false,
            audioContainer = "m4a",
            audioBitrateKbps = 192
        )
        assertFalse(dashVideoArgs.contains("aac_adtstoasc"))

        val mp3Args = FallbackMediaRules.buildAudioExtractArgs("in.tmp", "out.mp3", "mp3", 320)
        assertEquals(
            listOf("-y", "-i", "in.tmp", "-vn", "-c:a", "libmp3lame", "-b:a", "320k", "out.mp3"),
            mp3Args
        )
    }

    @Test
    fun classifyMediaCandidate_parsesMetaEfgAudioAndDashHintsAndRejectsByteend() {
        assertNull(FallbackMediaRules.classifyMediaCandidate("https://cdn.example.com/video.mp4?byteend=65536"))

        val audioEfg = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"vencode_tag":"xpv_progressive.VI_USECASE_PRODUCT_TYPE..C3.0.progressive_audio"}""".toByteArray()
        )
        val audioCand = FallbackMediaRules.classifyMediaCandidate(
            "https://instagram.fclo1-4.fna.fbcdn.net/o1/v/t2/f2/m86/sample.mp4?efg=$audioEfg&_nc_cat=102"
        )
        assertNotNull(audioCand)
        assertEquals(StreamKind.AUDIO_ONLY, audioCand!!.kind)

        val dashEfg = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"vencode_tag":"dash_baseline_720p_v1"}""".toByteArray()
        )
        val dashCand = FallbackMediaRules.classifyMediaCandidate(
            "https://instagram.fclo1-4.fna.fbcdn.net/o1/v/t2/f2/m86/video.mp4?efg=$dashEfg"
        )
        assertNotNull(dashCand)
        assertEquals(StreamKind.PROGRESSIVE_MP4, dashCand!!.kind)
        assertTrue(dashCand.isVideoOnly)
        assertFalse(dashCand.hasAudio)
        assertEquals(720, dashCand.height)
    }

    @Test
    fun extractMediaFromHtml_collectsContentImagesAndSkipsSiteLogos() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="Photo by alice" />
                <meta property="og:image" content="https://static.cdninstagram.com/rsrc.php/instagram_logo.png" />
                <meta property="og:image:secure_url" content="https://cdn.example.com/photos/alice_portrait.jpg" />
                <link rel="image_src" href="/images/fallback_pic.webp" />
              </head>
              <body>
                <script>
                  window.__POST__ = {"display_url":"https:\/\/scontent-iad3-1.cdninstagram.com\/v\/t51.2885-15\/full_res.jpg\u0026stp=1"};
                </script>
              </body>
            </html>
        """.trimIndent()

        val result = FallbackMediaRules.extractMediaFromHtml(html, "https://www.instagram.com/p/XYZ123/")
        assertEquals("Photo by alice", result.title)
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.imageUrls.contains("https://scontent-iad3-1.cdninstagram.com/v/t51.2885-15/full_res.jpg&stp=1"))
        assertTrue(result.imageUrls.contains("https://cdn.example.com/photos/alice_portrait.jpg"))
        assertTrue(result.imageUrls.contains("https://www.instagram.com/images/fallback_pic.webp"))
        assertFalse(result.imageUrls.any { it.contains("rsrc.php") })
    }
}

package com.deniscerri.ytdl

import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.util.QualityCapsuleUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityCapsuleUtilTest {

    @Test
    fun resolveEffectiveResolutionHeight_handlesLandscapePortraitAndFormatNote() {
        val landscape1080 = Format(format_id = "137", _width = "1920", _height = "1080")
        val portraitReels1080 = Format(format_id = "reel_1080", _width = "1080", _height = "1920")
        val portraitTikTok720 = Format(format_id = "tt_720", _width = "720", _height = "1280")
        val uhd4k = Format(format_id = "313", _width = "3840", _height = "2160")
        val note720p60 = Format(format_id = "298", format_note = "720p60")
        val note4k = Format(format_id = "hd4k", format_note = "4K HDR")
        val note2k = Format(format_id = "hd2k", format_note = "2K QHD")

        assertEquals(1080, QualityCapsuleUtil.resolveEffectiveResolutionHeight(landscape1080))
        assertEquals(1080, QualityCapsuleUtil.resolveEffectiveResolutionHeight(portraitReels1080))
        assertEquals(720, QualityCapsuleUtil.resolveEffectiveResolutionHeight(portraitTikTok720))
        assertEquals(2160, QualityCapsuleUtil.resolveEffectiveResolutionHeight(uhd4k))
        assertEquals(720, QualityCapsuleUtil.resolveEffectiveResolutionHeight(note720p60))
        assertEquals(2160, QualityCapsuleUtil.resolveEffectiveResolutionHeight(note4k))
        assertEquals(1440, QualityCapsuleUtil.resolveEffectiveResolutionHeight(note2k))
    }

    @Test
    fun buildVideoResolutionCapsules_deduplicatesPrefersMp4H264PairsAudioAndSortsDescending() {
        val video1080Vp9 = Format(
            format_id = "248",
            container = "webm",
            vcodec = "vp9",
            acodec = "none",
            filesize = 18_000_000L,
            tbr = "3500",
            fps = "30",
            _width = "1920",
            _height = "1080"
        )
        val video1080Avc = Format(
            format_id = "137",
            container = "mp4",
            vcodec = "avc1.640028",
            acodec = "none",
            filesize = 20_000_000L,
            tbr = "3200",
            fps = "60",
            _width = "1920",
            _height = "1080"
        )
        val video720Muxed = Format(
            format_id = "22",
            container = "mp4",
            vcodec = "avc1.64001F",
            acodec = "mp4a.40.2",
            filesize = 12_000_000L,
            fps = "30",
            _width = "1280",
            _height = "720"
        )
        val video4k = Format(
            format_id = "313",
            container = "webm",
            vcodec = "vp9",
            acodec = "none",
            filesize = 60_000_000L,
            fps = "30",
            _width = "3840",
            _height = "2160"
        )
        val storyboard = Format(
            format_id = "sb0",
            container = "mhtml",
            vcodec = "images",
            format_note = "storyboard",
            _width = "160",
            _height = "90"
        )
        val staleGeneric = Format(
            format_id = "1080p_ytdlnisgeneric",
            container = "mp4",
            vcodec = "h264",
            format_note = "1080p_ytdlnisgeneric"
        )
        val audioOpus = Format(
            format_id = "251",
            container = "webm",
            vcodec = "none",
            acodec = "opus",
            filesize = 3_500_000L,
            tbr = "160"
        )
        val audioM4a = Format(
            format_id = "140",
            container = "m4a",
            vcodec = "none",
            acodec = "mp4a.40.2",
            filesize = 3_000_000L,
            tbr = "128"
        )

        val capsules = QualityCapsuleUtil.buildVideoResolutionCapsules(
            listOf(
                video720Muxed,
                video1080Vp9,
                video1080Avc,
                video4k,
                storyboard,
                staleGeneric,
                audioOpus,
                audioM4a
            )
        )

        assertEquals(listOf("4K", "1080p", "720p"), capsules.map { it.label })
        assertEquals(listOf(2160, 1080, 720), capsules.map { it.height })
        assertTrue(capsules.none { it.isGeneric })

        val cap1080 = capsules[1]
        assertEquals("137", cap1080.videoFormat.format_id)
        assertEquals("60fps", cap1080.fpsLabel)
        assertNotNull(cap1080.pairedAudioFormat)
        assertEquals("140", cap1080.pairedAudioFormat?.format_id)
        assertEquals(23_000_000L, cap1080.estimatedBytes)

        val cap720 = capsules[2]
        assertEquals("22", cap720.videoFormat.format_id)
        assertEquals("", cap720.fpsLabel)
        assertNull(cap720.pairedAudioFormat)
        assertEquals(12_000_000L, cap720.estimatedBytes)
    }

    @Test
    fun buildVideoResolutionCapsules_fallsBackToGenericCapsulesWhenEmptyOrOnlyGeneric() {
        val emptyCapsules = QualityCapsuleUtil.buildVideoResolutionCapsules(emptyList())
        assertEquals(listOf("4K", "1080p", "720p", "480p", "360p"), emptyCapsules.map { it.label })
        assertEquals(
            listOf(
                "2160p_ytdlnisgeneric",
                "1080p_ytdlnisgeneric",
                "720p_ytdlnisgeneric",
                "480p_ytdlnisgeneric",
                "360p_ytdlnisgeneric"
            ),
            emptyCapsules.map { it.videoFormat.format_id }
        )
        assertTrue(emptyCapsules.all { it.isGeneric })

        val audioOnly = listOf(Format(format_id = "140", container = "m4a", vcodec = "none", acodec = "mp4a.40.2"))
        val audioOnlyFallback = QualityCapsuleUtil.buildVideoResolutionCapsules(audioOnly)
        assertEquals(5, audioOnlyFallback.size)
        assertTrue(audioOnlyFallback.all { it.isGeneric })
    }

    @Test
    fun buildAudioQualityCapsules_producesStandardBitratesAndEstimatesSize() {
        val lowAudio = Format(format_id = "139", container = "m4a", vcodec = "none", acodec = "mp4a", tbr = "48", filesize = 1_000_000L)
        val highAudio = Format(format_id = "251", container = "webm", vcodec = "none", acodec = "opus", tbr = "160", filesize = 4_000_000L)

        val capsules = QualityCapsuleUtil.buildAudioQualityCapsules(
            allFormats = listOf(lowAudio, highAudio),
            durationSeconds = 100L
        )
        assertEquals(listOf(320, 256, 192, 128, 64), capsules.map { it.bitrateKbps })
        assertEquals(listOf("320 kbps", "256 kbps", "192 kbps", "128 kbps", "64 kbps"), capsules.map { it.label })
        assertFalse(capsules.first().isGeneric)
        assertEquals("251", capsules.first().audioFormat.format_id)
        // 320 kbps for 100s = (320 * 1000 / 8) * 100 = 4,000,000 bytes
        assertEquals(4_000_000L, capsules.first().estimatedBytes)
        // 128 kbps for 100s = 1,600,000 bytes
        assertEquals(1_600_000L, capsules[3].estimatedBytes)

        val genericCapsules = QualityCapsuleUtil.buildAudioQualityCapsules(emptyList(), durationSeconds = 0L)
        assertTrue(genericCapsules.all { it.isGeneric })
        assertEquals("ba", genericCapsules.first().audioFormat.format_id)
        assertEquals(0L, genericCapsules.first().estimatedBytes)
    }

    @Test
    fun parseDurationToSeconds_and_formatCompactSize_handleFormatsAndBoundaries() {
        assertEquals(225L, QualityCapsuleUtil.parseDurationToSeconds("3:45"))
        assertEquals(3723L, QualityCapsuleUtil.parseDurationToSeconds("1:02:03"))
        assertEquals(90L, QualityCapsuleUtil.parseDurationToSeconds("90"))
        assertEquals(0L, QualityCapsuleUtil.parseDurationToSeconds("LIVE"))
        assertEquals(0L, QualityCapsuleUtil.parseDurationToSeconds("-1"))
        assertEquals(0L, QualityCapsuleUtil.parseDurationToSeconds(""))

        assertEquals("", QualityCapsuleUtil.formatCompactSize(0L))
        assertEquals("5 KB", QualityCapsuleUtil.formatCompactSize(5120L))
        assertEquals("5.0 MB", QualityCapsuleUtil.formatCompactSize(5L * 1024L * 1024L))
        assertEquals("2.00 GB", QualityCapsuleUtil.formatCompactSize(2L * 1024L * 1024L * 1024L))
    }
}

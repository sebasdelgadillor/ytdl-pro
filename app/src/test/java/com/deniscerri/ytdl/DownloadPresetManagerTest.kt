package com.deniscerri.ytdl

import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.AudioPreferences
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.VideoPreferences
import com.deniscerri.ytdl.util.DownloadPresetManager
import com.deniscerri.ytdl.util.DownloadPresetManager.DownloadPreset
import com.deniscerri.ytdl.util.QualityCapsuleUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPresetManagerTest {

    @Test
    fun serializeAndDeserializeCustomPresets_roundTripsAndHandlesCorruptInput() {
        val custom = listOf(
            DownloadPreset(
                id = "custom_1",
                name = "Clips 720p",
                type = "video",
                maxVideoHeight = 720,
                container = "mp4",
                compatibilityMode = true,
                embedSubtitles = true,
                embedThumbnail = false,
                customPath = "/sdcard/Download/Clips",
                isBuiltIn = true // should normalize to false when serialized/deserialized
            ),
            DownloadPreset(
                id = "custom_2",
                name = "Podcast 128k",
                type = "audio",
                container = "mp3",
                audioBitrateKbps = 128,
                embedThumbnail = true,
                isBuiltIn = false
            )
        )

        val json = DownloadPresetManager.serializeCustomPresets(custom)
        val restored = DownloadPresetManager.deserializeCustomPresets(json)

        assertEquals(2, restored.size)
        assertEquals("custom_1", restored[0].id)
        assertEquals("Clips 720p", restored[0].name)
        assertTrue(restored[0].compatibilityMode)
        assertTrue(restored[0].embedSubtitles)
        assertFalse(restored[0].isBuiltIn)
        assertEquals(128, restored[1].audioBitrateKbps)

        assertTrue(DownloadPresetManager.deserializeCustomPresets(null).isEmpty())
        assertTrue(DownloadPresetManager.deserializeCustomPresets("{broken json").isEmpty())
        assertTrue(DownloadPresetManager.deserializeCustomPresets("""[{"id":"","name":"NoId"}]""").isEmpty())
    }

    @Test
    fun pickVideoAndAudioCapsules_selectExactFallbackAndMaxQuality() {
        val f2160 = Format(format_id = "313", container = "webm", vcodec = "vp9", _width = "3840", _height = "2160")
        val f1080 = Format(format_id = "137", container = "mp4", vcodec = "avc1", _width = "1920", _height = "1080")
        val f480 = Format(format_id = "135", container = "mp4", vcodec = "avc1", _width = "854", _height = "480")
        val videoCapsules = QualityCapsuleUtil.buildVideoResolutionCapsules(listOf(f2160, f1080, f480))

        // Max quality (0) -> picks highest (2160)
        val best = DownloadPresetManager.pickVideoCapsuleForPreset(videoCapsules, maxHeight = 0)
        assertNotNull(best)
        assertEquals(2160, best!!.height)

        // <= 1080 -> picks 1080
        val cap1080 = DownloadPresetManager.pickVideoCapsuleForPreset(videoCapsules, maxHeight = 1080)
        assertEquals(1080, cap1080!!.height)

        // <= 720 when 720 absent -> picks next lower available (480)
        val cap720 = DownloadPresetManager.pickVideoCapsuleForPreset(videoCapsules, maxHeight = 720)
        assertEquals(480, cap720!!.height)

        // <= 360 when only higher exist -> falls back to lowest positive (480)
        val cap360 = DownloadPresetManager.pickVideoCapsuleForPreset(videoCapsules, maxHeight = 360)
        assertEquals(480, cap360!!.height)

        val audioCapsules = QualityCapsuleUtil.buildAudioQualityCapsules(
            listOf(Format(format_id = "140", container = "m4a", vcodec = "none", acodec = "mp4a", tbr = "128")),
            durationSeconds = 180L
        )
        assertEquals(320, DownloadPresetManager.pickAudioCapsuleForPreset(audioCapsules, 320)!!.bitrateKbps)
        assertEquals(128, DownloadPresetManager.pickAudioCapsuleForPreset(audioCapsules, 128)!!.bitrateKbps)
        // Closest fallback (250 -> 256)
        assertEquals(256, DownloadPresetManager.pickAudioCapsuleForPreset(audioCapsules, 250)!!.bitrateKbps)
    }

    @Test
    fun applyPresetToDownloadItem_updatesVideoAudioAndImageConfigurations() {
        val formats = mutableListOf(
            Format(format_id = "137", container = "mp4", vcodec = "avc1", _width = "1920", _height = "1080"),
            Format(format_id = "136", container = "mp4", vcodec = "avc1", _width = "1280", _height = "720"),
            Format(format_id = "140", container = "m4a", vcodec = "none", acodec = "mp4a", tbr = "128")
        )
        val baseItem = DownloadItem(
            id = 1L,
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            title = "Sample",
            author = "Channel",
            thumb = "",
            duration = "3:32",
            type = DownloadType.video,
            format = formats[0],
            container = "",
            downloadSections = "",
            allFormats = formats,
            downloadPath = "/sdcard/Download",
            website = "youtube.com",
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(),
            videoPreferences = VideoPreferences(),
            extraCommands = "",
            customFileNameTemplate = "",
            SaveThumb = false,
            status = "Queued",
            downloadStartTime = 0L,
            logID = null
        )

        val builtIns = DownloadPresetManager.getDefaultBuiltInPresets()
        val video720Preset = builtIns.first { it.id == "builtin_video_720" }
        DownloadPresetManager.applyPresetToDownloadItem(baseItem, video720Preset)
        assertEquals(DownloadType.video, baseItem.type)
        assertEquals("mp4", baseItem.container)
        assertTrue(baseItem.videoPreferences.compatibilityMode)
        assertEquals("136", baseItem.format.format_id)
        assertEquals(listOf("140"), baseItem.videoPreferences.audioFormatIDs)

        val audio320Preset = builtIns.first { it.id == "builtin_audio_320" }
        DownloadPresetManager.applyPresetToDownloadItem(baseItem, audio320Preset)
        assertEquals(DownloadType.audio, baseItem.type)
        assertEquals("mp3", baseItem.container)
        assertEquals("320k", baseItem.audioPreferences.bitrate)
        assertTrue(baseItem.audioPreferences.embedThumb)
        assertEquals("140", baseItem.format.format_id)

        val imagePngPreset = builtIns.first { it.id == "builtin_image_png" }
        DownloadPresetManager.applyPresetToDownloadItem(baseItem, imagePngPreset)
        assertEquals(DownloadType.image, baseItem.type)
        assertEquals("png", baseItem.container)
        assertEquals("png", baseItem.format.container)
    }
}

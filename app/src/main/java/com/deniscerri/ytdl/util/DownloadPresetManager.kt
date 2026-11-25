package com.deniscerri.ytdl.util

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale
import kotlin.math.abs

/**
 * Manages quick download presets ("perfiles rápidos") stored locally while keeping
 * all Advanced Options accessible on every download sheet.
 */
object DownloadPresetManager {

    const val PREFS_KEY_CUSTOM_PRESETS = "quick_download_presets_v1"
    const val PREFS_KEY_BATCH_PRESET_ID = "browser_batch_preset_id_v1"
    const val AUTO_BATCH_PRESET_ID = "auto"

    private const val MAX_CUSTOM_PRESETS = 20
    private val gson = Gson()
    private val listType = object : TypeToken<List<DownloadPreset>>() {}.type

    data class DownloadPreset(
        val id: String,
        val name: String,
        val type: String, // "video", "audio", "image"
        val maxVideoHeight: Int = 0, // 0 = best available
        val container: String = "",
        val compatibilityMode: Boolean = false,
        val embedSubtitles: Boolean = false,
        val audioBitrateKbps: Int = 0,
        val embedThumbnail: Boolean = true,
        val customPath: String = "",
        val isBuiltIn: Boolean = false
    ) {
        fun downloadType(): DownloadType = when (type.lowercase(Locale.US)) {
            "audio" -> DownloadType.audio
            "image" -> DownloadType.image
            else -> DownloadType.video
        }
    }

    fun getBuiltInPresets(context: Context): List<DownloadPreset> = listOf(
        DownloadPreset(
            id = "builtin_video_1080",
            name = context.getString(R.string.preset_video_1080),
            type = "video",
            maxVideoHeight = 1080,
            container = "mp4",
            compatibilityMode = false,
            embedThumbnail = false,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_video_720",
            name = context.getString(R.string.preset_video_720_light),
            type = "video",
            maxVideoHeight = 720,
            container = "mp4",
            compatibilityMode = true,
            embedThumbnail = false,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_video_max",
            name = context.getString(R.string.preset_video_max),
            type = "video",
            maxVideoHeight = 0,
            container = "mp4",
            compatibilityMode = false,
            embedThumbnail = false,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_audio_320",
            name = context.getString(R.string.preset_audio_320),
            type = "audio",
            container = "mp3",
            audioBitrateKbps = 320,
            embedThumbnail = true,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_audio_128",
            name = context.getString(R.string.preset_audio_128),
            type = "audio",
            container = "mp3",
            audioBitrateKbps = 128,
            embedThumbnail = true,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_image_jpg",
            name = context.getString(R.string.preset_image_jpg),
            type = "image",
            container = "jpg",
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_image_png",
            name = context.getString(R.string.preset_image_png),
            type = "image",
            container = "png",
            isBuiltIn = true
        )
    )

    fun getDefaultBuiltInPresets(): List<DownloadPreset> = listOf(
        DownloadPreset(
            id = "builtin_video_1080",
            name = "1080p MP4",
            type = "video",
            maxVideoHeight = 1080,
            container = "mp4",
            embedThumbnail = false,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_video_720",
            name = "Ligero 720p",
            type = "video",
            maxVideoHeight = 720,
            container = "mp4",
            compatibilityMode = true,
            embedThumbnail = false,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_video_max",
            name = "Máxima calidad",
            type = "video",
            maxVideoHeight = 0,
            container = "mp4",
            embedThumbnail = false,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_audio_320",
            name = "MP3 320k",
            type = "audio",
            container = "mp3",
            audioBitrateKbps = 320,
            embedThumbnail = true,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_audio_128",
            name = "Audio 128k",
            type = "audio",
            container = "mp3",
            audioBitrateKbps = 128,
            embedThumbnail = true,
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_image_jpg",
            name = "Imagen JPG",
            type = "image",
            container = "jpg",
            isBuiltIn = true
        ),
        DownloadPreset(
            id = "builtin_image_png",
            name = "Imagen PNG",
            type = "image",
            container = "png",
            isBuiltIn = true
        )
    )

    fun serializeCustomPresets(presets: List<DownloadPreset>): String {
        val sanitized = presets
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .take(MAX_CUSTOM_PRESETS)
            .map { it.copy(isBuiltIn = false) }
        return gson.toJson(sanitized)
    }

    fun deserializeCustomPresets(json: String?): List<DownloadPreset> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val parsed: List<DownloadPreset>? = gson.fromJson(json, listType)
            parsed.orEmpty()
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .take(MAX_CUSTOM_PRESETS)
                .map { it.copy(isBuiltIn = false) }
        }.getOrDefault(emptyList())
    }

    fun getCustomPresets(context: Context): List<DownloadPreset> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return deserializeCustomPresets(prefs.getString(PREFS_KEY_CUSTOM_PRESETS, null))
    }

    fun getAllPresets(context: Context): List<DownloadPreset> =
        getBuiltInPresets(context) + getCustomPresets(context)

    fun saveCustomPreset(context: Context, preset: DownloadPreset) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val clean = preset.copy(
            id = preset.id.ifBlank { "custom_${System.currentTimeMillis()}" },
            name = preset.name.trim(),
            isBuiltIn = false
        )
        if (clean.name.isBlank()) return
        val existing = getCustomPresets(context).filterNot { it.id == clean.id }.toMutableList()
        existing.add(0, clean)
        prefs.edit {
            putString(PREFS_KEY_CUSTOM_PRESETS, serializeCustomPresets(existing))
        }
    }

    fun deleteCustomPreset(context: Context, presetId: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val updated = getCustomPresets(context).filterNot { it.id == presetId }
        prefs.edit {
            putString(PREFS_KEY_CUSTOM_PRESETS, serializeCustomPresets(updated))
        }
    }

    fun getLastUsedBatchPresetId(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(PREFS_KEY_BATCH_PRESET_ID, AUTO_BATCH_PRESET_ID)
            ?.takeIf { it.isNotBlank() }
            ?: AUTO_BATCH_PRESET_ID
    }

    fun setLastUsedBatchPresetId(context: Context, presetId: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit {
            putString(PREFS_KEY_BATCH_PRESET_ID, presetId.ifBlank { AUTO_BATCH_PRESET_ID })
        }
    }

    fun pickVideoCapsuleForPreset(
        capsules: List<QualityCapsuleUtil.VideoCapsule>,
        maxHeight: Int
    ): QualityCapsuleUtil.VideoCapsule? {
        if (capsules.isEmpty()) return null
        if (maxHeight <= 0) {
            return capsules.maxByOrNull { it.height } ?: capsules.first()
        }
        return capsules.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }
            ?: capsules.filter { it.height > 0 }.minByOrNull { it.height }
            ?: capsules.first()
    }

    fun pickAudioCapsuleForPreset(
        capsules: List<QualityCapsuleUtil.AudioCapsule>,
        targetBitrateKbps: Int
    ): QualityCapsuleUtil.AudioCapsule? {
        if (capsules.isEmpty()) return null
        val target = if (targetBitrateKbps > 0) targetBitrateKbps else 192
        return capsules.firstOrNull { it.bitrateKbps == target }
            ?: capsules.minByOrNull { abs(it.bitrateKbps - target) }
    }

    fun applyPresetToDownloadItem(item: DownloadItem, preset: DownloadPreset) {
        val targetType = preset.downloadType()
        item.type = targetType
        if (preset.customPath.isNotBlank()) {
            item.downloadPath = preset.customPath
        }

        when (targetType) {
            DownloadType.video -> {
                item.container = if (preset.compatibilityMode) {
                    "mp4"
                } else {
                    preset.container.ifBlank { "mp4" }
                }
                item.videoPreferences.compatibilityMode = preset.compatibilityMode
                if (preset.compatibilityMode) {
                    item.videoPreferences.recodeVideo = false
                }
                item.videoPreferences.embedSubs = preset.embedSubtitles
                item.videoPreferences.embedThumbnail = preset.embedThumbnail

                val capsules = QualityCapsuleUtil.buildVideoResolutionCapsules(item.allFormats)
                val chosen = pickVideoCapsuleForPreset(capsules, preset.maxVideoHeight)
                if (chosen != null) {
                    item.format = chosen.videoFormat
                    item.videoPreferences.audioFormatIDs.clear()
                    chosen.pairedAudioFormat?.let {
                        item.videoPreferences.audioFormatIDs.add(it.format_id)
                    }
                }
            }
            DownloadType.audio -> {
                val ext = preset.container.lowercase(Locale.US).ifBlank { "mp3" }
                item.container = ext
                val kbps = preset.audioBitrateKbps.takeIf { it > 0 } ?: 192
                item.audioPreferences.bitrate = "${kbps}k"
                item.audioPreferences.embedThumb = if (ext == "wav") false else preset.embedThumbnail

                val durationSec = QualityCapsuleUtil.parseDurationToSeconds(item.duration)
                val capsules = QualityCapsuleUtil.buildAudioQualityCapsules(item.allFormats, durationSec)
                val chosen = pickAudioCapsuleForPreset(capsules, kbps)
                if (chosen != null) {
                    item.format = chosen.audioFormat
                }
            }
            DownloadType.image -> {
                val ext = preset.container.lowercase(Locale.US)
                    .takeIf { it in listOf("jpg", "png", "webp") }
                    ?: "jpg"
                item.container = ext
                item.format.container = ext
            }
            else -> Unit
        }
    }
}

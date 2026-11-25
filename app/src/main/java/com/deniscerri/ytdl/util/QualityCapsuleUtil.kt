package com.deniscerri.ytdl.util

import com.deniscerri.ytdl.database.models.Format
import java.util.Locale

object QualityCapsuleUtil {

    data class VideoCapsule(
        val label: String,
        val height: Int,
        val fpsLabel: String,
        val videoFormat: Format,
        val pairedAudioFormat: Format?,
        val estimatedBytes: Long,
        val isGeneric: Boolean
    )

    data class AudioCapsule(
        val label: String,
        val bitrateKbps: Int,
        val audioFormat: Format,
        val estimatedBytes: Long,
        val isGeneric: Boolean
    )

    data class ImageFormatCapsule(
        val label: String,
        val extension: String
    )

    val IMAGE_FORMAT_CAPSULES: List<ImageFormatCapsule> = listOf(
        ImageFormatCapsule("JPG", "jpg"),
        ImageFormatCapsule("PNG", "png"),
        ImageFormatCapsule("WEBP", "webp")
    )

    private val RESOLUTION_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
    private val STANDARD_AUDIO_BITRATES = listOf(320, 256, 192, 128, 64)

    fun resolveEffectiveResolutionHeight(format: Format): Int {
        val w = format.width
        val h = format.height
        if (w != null && h != null && w > 0 && h > 0) {
            return minOf(w, h)
        }
        if (h != null && h > 0) {
            return h
        }
        val note = format.format_note.trim()
        val match = RESOLUTION_REGEX.find(note)
        if (match != null) {
            val parsed = match.groupValues[1].toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        val lower = note.lowercase(Locale.US)
        if (lower.contains("4k") || lower.contains("uhd")) return 2160
        if (lower.contains("2k") || lower.contains("qhd")) return 1440
        return 0
    }

    fun bucketResolution(effectiveHeight: Int): Pair<Int, String> {
        return when {
            effectiveHeight >= 2000 -> 2160 to "4K"
            effectiveHeight in 1300..1999 -> 1440 to "1440p"
            effectiveHeight in 900..1299 -> 1080 to "1080p"
            effectiveHeight in 640..899 -> 720 to "720p"
            effectiveHeight in 420..639 -> 480 to "480p"
            effectiveHeight in 300..419 -> 360 to "360p"
            effectiveHeight in 1..299 -> 240 to "240p"
            else -> 0 to "Best"
        }
    }

    fun buildVideoResolutionCapsules(allFormats: List<Format>): List<VideoCapsule> {
        val realVideoFormats = allFormats.filter { fmt ->
            val isGeneric = fmt.format_id.contains("ytdlnisgeneric", ignoreCase = true) ||
                fmt.format_note.contains("ytdlnisgeneric", ignoreCase = true)
            val isStoryboard = fmt.container.equals("mhtml", ignoreCase = true) ||
                fmt.format_note.contains("storyboard", ignoreCase = true)
            val hasVideo = (fmt.vcodec.isNotBlank() && !fmt.vcodec.equals("none", ignoreCase = true)) ||
                resolveEffectiveResolutionHeight(fmt) > 0
            val isPureAudio = fmt.vcodec.equals("none", ignoreCase = true) &&
                fmt.acodec.isNotBlank() && !fmt.acodec.equals("none", ignoreCase = true)
            !isGeneric && !isStoryboard && !isPureAudio && hasVideo
        }

        if (realVideoFormats.isEmpty()) {
            return buildGenericVideoCapsules()
        }

        val audioFormats = allFormats.filter { fmt ->
            val isGeneric = fmt.format_id.contains("ytdlnisgeneric", ignoreCase = true) ||
                fmt.format_note.contains("ytdlnisgeneric", ignoreCase = true)
            val isAudioOnly = (fmt.vcodec.isBlank() || fmt.vcodec.equals("none", ignoreCase = true)) &&
                fmt.acodec.isNotBlank() && !fmt.acodec.equals("none", ignoreCase = true)
            !isGeneric && isAudioOnly
        }
        val bestAudioForMp4 = audioFormats.maxByOrNull { scoreAudioForMp4(it) }

        val grouped = realVideoFormats.groupBy { bucketResolution(resolveEffectiveResolutionHeight(it)) }
        val hasKnownBucket = grouped.keys.any { it.first > 0 }

        val capsules = grouped.entries
            .filter { (bucket, _) -> !hasKnownBucket || bucket.first > 0 }
            .map { (bucket, formatsInBucket) ->
                val chosenVideo = formatsInBucket.maxByOrNull { scoreVideoForMp4(it) }!!
                val hasEmbeddedAudio = chosenVideo.acodec.isNotBlank() &&
                    !chosenVideo.acodec.equals("none", ignoreCase = true)
                val pairedAudio = if (hasEmbeddedAudio) null else bestAudioForMp4
                val estimatedBytes = chosenVideo.filesize.coerceAtLeast(0L) +
                    (pairedAudio?.filesize?.coerceAtLeast(0L) ?: 0L)
                val fpsVal = chosenVideo.fps?.toFloatOrNull()?.toInt() ?: 0
                val fpsLabel = if (fpsVal >= 50) "${fpsVal}fps" else ""
                VideoCapsule(
                    label = bucket.second,
                    height = bucket.first,
                    fpsLabel = fpsLabel,
                    videoFormat = chosenVideo,
                    pairedAudioFormat = pairedAudio,
                    estimatedBytes = estimatedBytes,
                    isGeneric = false
                )
            }
            .sortedByDescending { it.height }

        return capsules.ifEmpty { buildGenericVideoCapsules() }
    }

    private fun buildGenericVideoCapsules(): List<VideoCapsule> {
        val specs = listOf(
            Triple("4K", 2160, "2160p_ytdlnisgeneric"),
            Triple("1080p", 1080, "1080p_ytdlnisgeneric"),
            Triple("720p", 720, "720p_ytdlnisgeneric"),
            Triple("480p", 480, "480p_ytdlnisgeneric"),
            Triple("360p", 360, "360p_ytdlnisgeneric")
        )
        return specs.map { (label, height, id) ->
            VideoCapsule(
                label = label,
                height = height,
                fpsLabel = "",
                videoFormat = Format(
                    format_id = id,
                    container = "mp4",
                    vcodec = "h264",
                    format_note = label
                ),
                pairedAudioFormat = null,
                estimatedBytes = 0L,
                isGeneric = true
            )
        }
    }

    fun buildAudioQualityCapsules(
        allFormats: List<Format>,
        durationSeconds: Long = 0L
    ): List<AudioCapsule> {
        val realAudio = allFormats.filter { fmt ->
            val isGeneric = fmt.format_id.contains("ytdlnisgeneric", ignoreCase = true) ||
                fmt.format_note.contains("ytdlnisgeneric", ignoreCase = true)
            val isAudioOnly = (fmt.vcodec.isBlank() || fmt.vcodec.equals("none", ignoreCase = true)) &&
                fmt.acodec.isNotBlank() && !fmt.acodec.equals("none", ignoreCase = true)
            !isGeneric && isAudioOnly
        }
        val bestSource = realAudio.maxByOrNull { scoreAudioSource(it) }
            ?: Format(
                format_id = "ba",
                container = "mp3",
                acodec = "mp3",
                format_note = "Best audio"
            )
        val isGeneric = realAudio.isEmpty()

        return STANDARD_AUDIO_BITRATES.map { kbps ->
            val estimatedBytes = if (durationSeconds > 0L) {
                (kbps * 1000L / 8L) * durationSeconds
            } else {
                0L
            }
            AudioCapsule(
                label = "$kbps kbps",
                bitrateKbps = kbps,
                audioFormat = bestSource,
                estimatedBytes = estimatedBytes,
                isGeneric = isGeneric
            )
        }
    }

    fun parseDurationToSeconds(duration: String): Long {
        val trimmed = duration.trim()
        if (trimmed.isEmpty() || trimmed == "-1" || trimmed == "LIVE") return 0L
        if (!trimmed.contains(":")) {
            return trimmed.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        }
        val parts = trimmed.split(":").mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty()) return 0L
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]).coerceAtLeast(0L)
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]).coerceAtLeast(0L)
            else -> 0L
        }
    }

    fun formatCompactSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes < mb -> "${(bytes / kb).toLong().coerceAtLeast(1L)} KB"
            bytes < gb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            else -> String.format(Locale.US, "%.2f GB", bytes / gb)
        }
    }

    private fun scoreVideoForMp4(fmt: Format): Double {
        var score = 0.0
        val vcodec = fmt.vcodec.lowercase(Locale.US)
        val container = fmt.container.lowercase(Locale.US)
        if (vcodec.contains("avc1") || vcodec.contains("h264") || vcodec.contains("avc")) {
            score += 1000.0
        } else if (container == "mp4") {
            score += 700.0
        }
        if (container == "mp4") score += 250.0
        if (fmt.acodec.isNotBlank() && !fmt.acodec.equals("none", ignoreCase = true)) {
            score += 200.0
        }
        val tbr = fmt.tbr?.toDoubleOrNull() ?: 0.0
        score += tbr.coerceAtMost(50000.0) / 100.0
        if (fmt.filesize > 0L) {
            score += (fmt.filesize.toDouble() / (1024.0 * 1024.0)).coerceAtMost(200.0) * 0.1
        }
        return score
    }

    private fun scoreAudioForMp4(fmt: Format): Double {
        var score = 0.0
        val container = fmt.container.lowercase(Locale.US)
        val acodec = fmt.acodec.lowercase(Locale.US)
        if (container == "m4a" || acodec.contains("mp4a") || acodec.contains("aac")) {
            score += 500.0
        }
        val tbr = fmt.tbr?.toDoubleOrNull() ?: 0.0
        score += tbr
        if (fmt.filesize > 0L) {
            score += (fmt.filesize.toDouble() / 1_000_000.0).coerceAtMost(100.0)
        }
        return score
    }

    private fun scoreAudioSource(fmt: Format): Double {
        val tbr = fmt.tbr?.toDoubleOrNull() ?: 0.0
        val sizeScore = (fmt.filesize.toDouble() / 1_000_000.0).coerceAtMost(100.0)
        return tbr + sizeScore
    }
}

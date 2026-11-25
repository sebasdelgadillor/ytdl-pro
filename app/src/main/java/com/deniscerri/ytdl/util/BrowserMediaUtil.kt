package com.deniscerri.ytdl.util

import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.util.rules.MediaDetectionRules
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object BrowserMediaUtil {

    const val IMAGE_ONLY_FORMAT_ID = "image_only_detected"

    private val GENERIC_SITE_IMAGE_MARKERS = listOf(
        "favicon",
        "apple-touch-icon",
        "static.cdninstagram.com",
        "/rsrc.php",
        "rsrc.php",
        "default_profile",
        "blank.gif",
        "1x1",
        "/sprites/",
        "sprite",
        "og-logo",
        "site-logo",
        "brand-logo"
    )

    data class ChromiumVersion(
        val fullVersion: String,
        val majorVersion: String
    )

    data class ParsedNetscapeCookie(
        val domain: String,
        val includeSubdomains: Boolean,
        val path: String,
        val secure: Boolean,
        val expiryUnixSeconds: Long,
        val name: String,
        val value: String,
        val httpOnly: Boolean
    )

    private val CHROME_VERSION_REGEX = Regex("""Chrome/(\d+(?:\.\d+)*)""")
    private val WV_TOKEN_REGEX = Regex(""";\s*wv\b""", RegexOption.IGNORE_CASE)
    private val VERSION_TOKEN_REGEX = Regex("""Version/\d+(?:\.\d+)*\s+""", RegexOption.IGNORE_CASE)

    private val DATA_IMAGE_REGEX = Regex(
        """^data:image/(jpe?g|png|webp|gif);base64,[A-Za-z0-9+/=\s]{8,}$""",
        RegexOption.IGNORE_CASE
    )

    private val DIRECT_IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic"
    )

    private val NON_IMAGE_EXTENSIONS = setOf(
        "svg", "ico", "js", "css", "woff", "woff2", "ttf", "otf", "eot",
        "html", "htm", "php", "json", "xml", "txt", "map",
        "mp4", "m4v", "mov", "webm", "mkv", "flv", "m3u8", "mpd", "m4s", "ts",
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "vtt", "srt"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "webm", "mkv", "flv", "m3u8", "mpd", "m4s", "ts"
    )

    private val QUERY_IMAGE_FORMAT_REGEX = Regex(
        """[?&](?:format|fm|ext)=(?:jpe?g|png|webp|gif|avif)\b""",
        RegexOption.IGNORE_CASE
    )

    private val SRCSET_SPLIT_REGEX = Regex(""",\s+(?=https?://|/|data:)""")
    private val DURATION_BADGE_REGEX = Regex("""\b\d{1,2}:\d{2}\b""")
    private val VIDEO_QUERY_V_REGEX = Regex("""[?&]v=[A-Za-z0-9_-]+""")
    private val VIDEO_FILE_EXT_REGEX = Regex("""\.(mp4|m4v|mov|m3u8|mpd|webm)(\?|$)""", RegexOption.IGNORE_CASE)
    private val CANONICAL_SUBPAGE_REGEX = Regex(
        """^/(p|reel|reels|tv)/([^/]+)/(liked_by|comments|c/[^/]+)/?$""",
        RegexOption.IGNORE_CASE
    )
    private val GENERIC_PLACEHOLDER_PREFIXES = listOf(
        "no hay ninguna descripción de la foto",
        "no photo description available",
        "video player",
        "reproductor de video",
        "reproductor de vídeo",
        "foto del perfil",
        "foto de perfil",
        "imagen de perfil",
        "profile picture",
        "profile photo"
    )

    private fun rules(): MediaDetectionRules = WebRulesRegistry.current().mediaDetection

    fun extractChromiumVersion(defaultWebViewUa: String): ChromiumVersion {
        val match = CHROME_VERSION_REGEX.find(defaultWebViewUa)
        val full = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "136.0.0.0"
        val major = full.substringBefore('.').takeIf { it.isNotBlank() } ?: "136"
        return ChromiumVersion(fullVersion = full, majorVersion = major)
    }

    fun buildMobileChromeUserAgent(defaultWebViewUa: String): String {
        val cleaned = defaultWebViewUa
            .replace(WV_TOKEN_REGEX, "")
            .replace(VERSION_TOKEN_REGEX, "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return cleaned.ifBlank {
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
        }
    }

    fun buildDesktopChromeUserAgent(fullVersion: String): String {
        val safeVer = fullVersion.ifBlank { "136.0.0.0" }
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$safeVer Safari/537.36"
    }

    fun isLikelyContentImageUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.US)

        if (lower.startsWith("data:image/")) {
            return DATA_IMAGE_REGEX.matches(trimmed)
        }

        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false
        }

        val r = rules()
        if (r.rejectedImageSubstrings.any { lower.contains(it.lowercase(Locale.US)) }) {
            return false
        }

        val parsedUri = runCatching { URI(trimmed) }.getOrNull()
        val host = parsedUri?.host?.lowercase(Locale.US).orEmpty()
        val path = (parsedUri?.path ?: lower.substringBefore('?').substringBefore('#'))
            .lowercase(Locale.US)
        val ext = extractExtension(path)

        if (ext in VIDEO_EXTENSIONS || ext in NON_IMAGE_EXTENSIONS) {
            return false
        }

        if (ext in DIRECT_IMAGE_EXTENSIONS) {
            return true
        }

        if (QUERY_IMAGE_FORMAT_REGEX.containsMatchIn(lower)) {
            return true
        }

        if (host == "pbs.twimg.com" && path.contains("/media/")) {
            return true
        }

        if (r.trustedImageCdnMarkers.any { host.contains(it.lowercase(Locale.US)) }) {
            if (path.contains("/rsrc.php") || path.contains("/video/")) {
                return false
            }
            return true
        }

        return false
    }

    private fun extractExtension(lowerPath: String): String {
        val lastSegment = lowerPath.substringAfterLast('/')
            .substringBefore('&')
            .substringBefore(';')
        val dotIdx = lastSegment.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx == lastSegment.length - 1) return ""
        return lastSegment.substring(dotIdx + 1)
    }

    fun extractBestSrcFromSrcset(srcset: String): String? {
        val trimmed = srcset.trim()
        if (trimmed.isEmpty()) return null

        val entries = trimmed.split(SRCSET_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var bestUrl: String? = null
        var bestWeight = -1

        entries.forEachIndexed { idx, entry ->
            val parts = entry.split(Regex("""\s+"""))
            val rawUrl = parts.firstOrNull()?.trim().orEmpty()
            if (rawUrl.isEmpty()) return@forEachIndexed
            val descriptor = parts.getOrNull(1)?.trim()?.lowercase(Locale.US).orEmpty()
            val weight = when {
                descriptor.endsWith("w") -> descriptor.dropLast(1).toIntOrNull() ?: 1
                descriptor.endsWith("x") -> ((descriptor.dropLast(1).toFloatOrNull() ?: 1f) * 1000f).toInt()
                else -> 1 + idx
            }
            if (weight >= bestWeight) {
                bestWeight = weight
                bestUrl = rawUrl
            }
        }
        return bestUrl
    }

    fun scoreImageCandidate(url: String, width: Int, height: Int, source: String): Int {
        val lower = url.lowercase(Locale.US)
        val safeW = width.coerceAtLeast(0).toLong()
        val safeH = height.coerceAtLeast(0).toLong()
        var score = ((safeW * safeH).coerceAtMost(10_000_000L) / 500L).toInt()

        if (lower.contains("_n.jpg") || lower.contains("name=orig") || lower.contains("name=large")) {
            score += 1500
        }
        if (lower.contains("scontent") || lower.contains("cdninstagram.com") || lower.contains("pbs.twimg.com/media/")) {
            score += 600
        }
        if (source.contains("lightbox", ignoreCase = true) || source.contains("fullscreen", ignoreCase = true)) {
            score += 800
        } else if (source.contains("point", ignoreCase = true)) {
            score += 400
        }

        if ((width in 1 until 120) || (height in 1 until 120)) {
            score -= 3000
        }
        if (lower.contains("avatar") || lower.contains("profile_pic") || lower.contains("s150x150") || lower.contains("_s.jpg")) {
            score -= 800
        }
        return score
    }

    fun parseNetscapeCookieText(
        rawText: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000L
    ): List<ParsedNetscapeCookie> {
        if (rawText.isBlank()) return emptyList()
        val result = mutableListOf<ParsedNetscapeCookie>()
        val httpOnlyPrefix = "#HttpOnly_"

        rawText.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            var httpOnly = false
            val effectiveLine = if (trimmed.startsWith(httpOnlyPrefix, ignoreCase = true)) {
                httpOnly = true
                trimmed.substring(httpOnlyPrefix.length)
            } else if (trimmed.startsWith("#")) {
                return@forEach
            } else {
                trimmed
            }

            val parts = effectiveLine.split('\t', limit = 7)
            if (parts.size < 7) return@forEach

            val rawDomain = parts[0].trim()
            val includeSubdomains = parts[1].trim().equals("TRUE", ignoreCase = true)
            val path = parts[2].trim().ifEmpty { "/" }
            val secure = parts[3].trim().equals("TRUE", ignoreCase = true)
            val expiry = parts[4].trim().toLongOrNull() ?: 0L
            val name = parts[5].trim()
            val value = parts[6].trim()

            if (rawDomain.isEmpty() || name.isEmpty() || value.isEmpty()) return@forEach
            if (expiry > 0L && expiry < nowSeconds) return@forEach

            result.add(
                ParsedNetscapeCookie(
                    domain = rawDomain,
                    includeSubdomains = includeSubdomains,
                    path = path,
                    secure = secure,
                    expiryUnixSeconds = expiry,
                    name = name,
                    value = value,
                    httpOnly = httpOnly
                )
            )
        }
        return result
    }

    fun isDirectStaticImageFile(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("data:image/")) {
            return DATA_IMAGE_REGEX.matches(trimmed)
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false
        }
        if (rules().htmlPostUrlMarkers.any { lower.contains(it.lowercase(Locale.US)) }) {
            return false
        }
        return isLikelyContentImageUrl(trimmed)
    }

    fun canonicalizePermalinkUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val path = uri.path ?: return trimmed
        val match = CANONICAL_SUBPAGE_REGEX.matchEntire(path) ?: return trimmed
        val canonicalPath = "/${match.groupValues[1]}/${match.groupValues[2]}/"
        return runCatching {
            URI(uri.scheme, uri.authority, canonicalPath, uri.query, uri.fragment).toString()
        }.getOrDefault(trimmed)
    }

    fun isGenericPlaceholderTitle(title: String, domainDisplayName: String = ""): Boolean {
        val normalized = title.trim().replace(Regex("""\s+"""), " ")
        if (normalized.length <= 2) return true
        val lower = normalized.lowercase(Locale.US)
        val trimmedPunct = lower.trimEnd('.', '…', ':', '-', ' ').trim()
        if (trimmedPunct.length <= 2) return true

        val domainTrimmed = domainDisplayName.trim().lowercase(Locale.US)
        if (domainTrimmed.isNotEmpty() && (lower == domainTrimmed || trimmedPunct == domainTrimmed)) {
            return true
        }
        val knownDisplayNames = WebRulesRegistry.current().domains.displayNames.values
        if (knownDisplayNames.any {
                val d = it.trim().lowercase(Locale.US)
                d.isNotEmpty() && (lower == d || trimmedPunct == d)
            }
        ) {
            return true
        }

        val genericList = rules().genericPlaceholderTitles
        if (genericList.any {
                val g = it.trim().lowercase(Locale.US)
                g.isNotEmpty() && (lower == g || trimmedPunct == g)
            }
        ) {
            return true
        }

        return GENERIC_PLACEHOLDER_PREFIXES.any { prefix ->
            trimmedPunct.startsWith(prefix)
        }
    }

    private fun matchesPermalinkMarker(lowerUrl: String, lowerPath: String, marker: String): Boolean {
        val m = marker.lowercase(Locale.US)
        return if (m.startsWith("/") && m.endsWith("/")) {
            val idx = lowerPath.indexOf(m)
            idx >= 0 && lowerPath.substring(idx + m.length).trim('/').isNotEmpty()
        } else {
            lowerUrl.contains(m)
        }
    }

    fun isLikelyVideoPostUrl(url: String): Boolean {
        val canonical = canonicalizePermalinkUrl(url)
        val lower = canonical.trim().lowercase(Locale.US)
        if (lower.isEmpty()) return false
        if (isFeedOrListingUrl(canonical)) return false
        val r = rules()
        if (r.permalinkNoiseMarkers.any { lower.contains(it.lowercase(Locale.US)) }) return false
        val lowerPath = runCatching { URI(canonical).path?.lowercase(Locale.US) }.getOrNull().orEmpty()
        return r.videoPostUrlMarkers.any { matchesPermalinkMarker(lower, lowerPath, it) } ||
            VIDEO_QUERY_V_REGEX.containsMatchIn(lower) ||
            VIDEO_FILE_EXT_REGEX.containsMatchIn(lower)
    }

    fun isLikelyPhotoPageUrl(url: String): Boolean {
        val lower = url.trim().lowercase(Locale.US)
        if (lower.isEmpty()) return false
        return rules().photoPageUrlMarkers.any { lower.contains(it.lowercase(Locale.US)) }
    }

    fun isFeedOrListingUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return true
        val path = uri.path?.trimEnd('/')?.lowercase(Locale.US) ?: ""
        if (path == "/watch") {
            val query = uri.rawQuery?.lowercase(Locale.US).orEmpty()
            if (VIDEO_QUERY_V_REGEX.containsMatchIn("?$query")) {
                return false
            }
        }
        return path.isEmpty() || rules().feedPathMarkers.any { it.equals(path, ignoreCase = true) }
    }

    fun scorePermalinkCandidate(url: String, isDirectWrapper: Boolean = false): Int {
        val trimmed = url.trim()
        val canonical = canonicalizePermalinkUrl(trimmed)
        val wasSubpage = canonical != trimmed
        val lowerRaw = trimmed.lowercase(Locale.US)
        val lower = canonical.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return -1000
        val r = rules()
        if (r.permalinkNoiseMarkers.any {
                val m = it.lowercase(Locale.US)
                lowerRaw.contains(m) || lower.contains(m)
            }
        ) {
            return -1000
        }

        if (isFeedOrListingUrl(canonical)) return -500

        val parsed = runCatching { URI(canonical) }.getOrNull()
        val rawPath = parsed?.path?.lowercase(Locale.US).orEmpty()
        val path = rawPath.trimEnd('/')
        if (path.isEmpty() || path == "/") return -500

        var score = when {
            r.highPriorityPermalinkMarkers.any { matchesPermalinkMarker(lower, rawPath, it) } ||
                VIDEO_QUERY_V_REGEX.containsMatchIn(lower) -> 100
            r.generalPostPermalinkMarkers.any { lower.contains(it.lowercase(Locale.US)) } -> 60
            path.count { it == '/' } >= 2 -> 15
            else -> 0
        }

        if (wasSubpage && score > 0) {
            score -= 10
        }
        if (isDirectWrapper && score >= 0) {
            score += 20
        }
        return score
    }

    fun isContentPermalinkUrl(url: String): Boolean {
        return scorePermalinkCandidate(url) > 0
    }

    fun hasVideoPlayerHint(textOrLabel: String): Boolean {
        val lower = textOrLabel.trim().lowercase(Locale.US)
        if (lower.isEmpty()) return false
        return rules().videoHintKeywords.any { lower.contains(it.lowercase(Locale.US)) } ||
            DURATION_BADGE_REGEX.containsMatchIn(lower)
    }

    fun isGenericSiteImageUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return true
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("data:image/")) return false
        if (GENERIC_SITE_IMAGE_MARKERS.any { lower.contains(it) }) return true

        val parsed = runCatching { URI(trimmed) }.getOrNull()
        val path = (parsed?.path ?: lower.substringBefore('?').substringBefore('#')).lowercase(Locale.US)
        val ext = extractExtension(path)
        if (ext == "svg" || ext == "ico") return true

        return path.contains("/logo.") ||
            path.contains("/logo-") ||
            lower.contains("instagram_logo") ||
            lower.contains("fb_icon")
    }

    fun isImageFormat(format: Format): Boolean {
        val note = format.format_note.trim()
        if (format.format_id == IMAGE_ONLY_FORMAT_ID || note.equals("image_only", ignoreCase = true)) {
            return true
        }
        val container = format.container.trim().lowercase(Locale.US)
        if (container in DIRECT_IMAGE_EXTENSIONS) {
            return true
        }
        val formatUrl = format.url?.trim().orEmpty()
        return format.vcodec.equals("none", ignoreCase = true) &&
            format.acodec.equals("none", ignoreCase = true) &&
            formatUrl.isNotEmpty() &&
            isLikelyContentImageUrl(formatUrl)
    }

    fun isImageOnlyResult(result: ResultItem?): Boolean {
        if (result == null) return false
        if (result.formats.isNotEmpty()) {
            return result.formats.all { isImageFormat(it) }
        }
        return isDirectStaticImageFile(result.url)
    }

    fun isAudioOnlyResult(result: ResultItem?): Boolean {
        if (result == null || result.formats.isEmpty() || isImageOnlyResult(result)) return false
        return result.formats.all {
            it.format_note.contains("audio", ignoreCase = true) ||
                (it.vcodec.equals("none", ignoreCase = true) &&
                    it.acodec.isNotBlank() &&
                    !it.acodec.equals("none", ignoreCase = true))
        }
    }

    fun resolveBestImageUrl(result: ResultItem?): String? {
        if (result == null) return null
        val fromFormat = result.formats
            .firstOrNull { isImageFormat(it) && !it.url.isNullOrBlank() }
            ?.url
            ?.trim()
            ?.takeIf { !isGenericSiteImageUrl(it) || it.startsWith("data:image/", ignoreCase = true) }
        if (!fromFormat.isNullOrBlank()) return fromFormat

        val fromThumb = result.thumb.trim().takeIf {
            it.isNotBlank() &&
                (isLikelyContentImageUrl(it) || it.startsWith("data:image/", ignoreCase = true)) &&
                !isGenericSiteImageUrl(it)
        }
        if (!fromThumb.isNullOrBlank()) return fromThumb

        return result.url.trim().takeIf { isDirectStaticImageFile(it) }
    }

    fun inferImageExtensionFromUrl(url: String, mimeType: String = ""): String {
        val lowerMime = mimeType.substringBefore(';').trim().lowercase(Locale.US)
        when {
            lowerMime.contains("png") -> return "png"
            lowerMime.contains("webp") -> return "webp"
            lowerMime.contains("jpeg") || lowerMime.contains("jpg") -> return "jpg"
        }

        val lowerUrl = url.trim().lowercase(Locale.US)
        if (lowerUrl.startsWith("data:image/png")) return "png"
        if (lowerUrl.startsWith("data:image/webp")) return "webp"
        if (lowerUrl.startsWith("data:image/jpeg") || lowerUrl.startsWith("data:image/jpg")) return "jpg"

        val parsed = runCatching { URI(url.trim()) }.getOrNull()
        val path = (parsed?.path ?: lowerUrl.substringBefore('?').substringBefore('#')).lowercase(Locale.US)
        when (extractExtension(path)) {
            "png" -> return "png"
            "webp" -> return "webp"
            "jpg", "jpeg" -> return "jpg"
        }

        val queryMatch = Regex("""(?:^|[?&])(?:format|fm|ext)=([a-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(parsed?.rawQuery?.let { "?$it" } ?: lowerUrl)
            ?.groupValues?.getOrNull(1)
            ?.lowercase(Locale.US)
        return when (queryMatch) {
            "png" -> "png"
            "webp" -> "webp"
            else -> "jpg"
        }
    }

    fun inferCleanImageTitle(
        url: String,
        fallbackTitle: String = "",
        domainDisplayName: String = ""
    ): String {
        val cleanedFallback = fallbackTitle.trim().replace(Regex("""\s+"""), " ")
        if (cleanedFallback.isNotBlank() && !isGenericPlaceholderTitle(cleanedFallback, domainDisplayName)) {
            return cleanedFallback
        }

        val parsed = runCatching { URI(url.trim()) }.getOrNull()
        val rawSegment = parsed?.rawPath?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        if (rawSegment.isNotBlank()) {
            val decoded = runCatching {
                URLDecoder.decode(rawSegment, StandardCharsets.UTF_8.name())
            }.getOrDefault(rawSegment)
            val stem = decoded.substringBeforeLast('.').trim()
            val lowerStem = stem.lowercase(Locale.US)
            val isPureHexNoise = stem.length > 32 && stem.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '-' || it == '_' }
            if (stem.length >= 2 &&
                !isPureHexNoise &&
                lowerStem !in setOf("photo", "image", "media", "picture", "img", "file", "download")
            ) {
                return stem
            }
        }

        val domain = DomainUtil.normalizeRootDomain(url)
        val displayFromRegistry = domain?.let { WebRulesRegistry.current().domains.displayNames[it] }
        return domainDisplayName.trim().ifBlank {
            displayFromRegistry ?: domain ?: "Image"
        }
    }

    fun buildImageOnlyResultItem(
        url: String,
        imageUrl: String,
        title: String = "",
        author: String = "",
        website: String = "",
        mimeType: String = ""
    ): ResultItem {
        val normalizedSite = website.ifBlank { DomainUtil.normalizeRootDomain(url).orEmpty() }
        val displayName = normalizedSite.let { WebRulesRegistry.current().domains.displayNames[it] } ?: ""
        val cleanTitle = inferCleanImageTitle(url, title, displayName)
        val ext = inferImageExtensionFromUrl(imageUrl, mimeType)
        return ResultItem(
            id = 0,
            url = url,
            title = cleanTitle,
            author = author.trim(),
            duration = "",
            thumb = imageUrl,
            website = normalizedSite,
            playlistTitle = "",
            formats = arrayListOf(
                Format(
                    format_id = IMAGE_ONLY_FORMAT_ID,
                    container = ext,
                    vcodec = "none",
                    acodec = "none",
                    format_note = "image_only",
                    url = imageUrl
                )
            ),
            urls = "",
            chapters = ArrayList(),
            playlistURL = ""
        )
    }

    private val CAROUSEL_SIZE_SEGMENT_REGEX = Regex(
        """/(?:[sp]\d+x\d+|c\d+\.\d+\.\d+\.\d+[a-z]?|dst-jpg_[^/]+)(?=/)""",
        RegexOption.IGNORE_CASE
    )
    private val CAROUSEL_SIZING_QUERY_PARAM_REGEX = Regex(
        """^(?:name|format|fm|w|h|width|height|size|quality|q|resize|fit|crop|stp|auto)$""",
        RegexOption.IGNORE_CASE
    )

    private fun canonicalCarouselAssetKey(url: String): String {
        val trimmed = url.trim()
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.lowercase(Locale.US)
        val host = parsed.host?.lowercase(Locale.US).orEmpty()
        val rawPath = (parsed.path ?: "").lowercase(Locale.US)
        val normalizedPath = CAROUSEL_SIZE_SEGMENT_REGEX.replace(rawPath, "")
        val rawQuery = parsed.rawQuery.orEmpty()
        if (host == "pbs.twimg.com" && normalizedPath.contains("/media/")) {
            return "$host$normalizedPath"
        }
        val keptParams = rawQuery.split('&')
            .filter { it.isNotBlank() }
            .filterNot { param ->
                val key = param.substringBefore('=').trim()
                CAROUSEL_SIZING_QUERY_PARAM_REGEX.matches(key)
            }
            .sorted()
            .joinToString("&")
        return if (keptParams.isNotEmpty()) "$host$normalizedPath?$keptParams" else "$host$normalizedPath"
    }

    private fun carouselVariantPreferenceScore(url: String): Int {
        val lower = url.lowercase(Locale.US)
        var score = scoreImageCandidate(url, 0, 0, "")
        when {
            lower.contains("name=orig") -> score += 500
            lower.contains("name=large") -> score += 300
            lower.contains("s1080x1080") || lower.contains("w=1080") || lower.contains("1080") -> score += 200
            lower.contains("s640x640") -> score += 100
        }
        return score * 10 + url.length.coerceAtMost(500)
    }

    fun deduplicateCarouselImageUrls(urls: List<String>): List<String> {
        val byKey = LinkedHashMap<String, String>()
        for (raw in urls) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val isDataImage = trimmed.startsWith("data:image/", ignoreCase = true)
            if (!isDataImage && !isLikelyContentImageUrl(trimmed)) continue
            if (isGenericSiteImageUrl(trimmed)) continue

            val key = if (isDataImage) trimmed else canonicalCarouselAssetKey(trimmed)
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = trimmed
            } else if (!isDataImage && carouselVariantPreferenceScore(trimmed) > carouselVariantPreferenceScore(existing)) {
                byKey[key] = trimmed
            }
        }
        return byKey.values.toList()
    }

    fun buildCarouselImageResultItems(
        pageUrl: String,
        imageUrls: List<String>,
        title: String = "",
        author: String = "",
        website: String = "",
        mimeType: String = ""
    ): List<ResultItem> {
        val deduped = deduplicateCarouselImageUrls(imageUrls)
        if (deduped.isEmpty()) return emptyList()
        if (deduped.size == 1) {
            return listOf(
                buildImageOnlyResultItem(
                    url = pageUrl,
                    imageUrl = deduped.first(),
                    title = title,
                    author = author,
                    website = website,
                    mimeType = mimeType
                )
            )
        }

        val normalizedSite = website.ifBlank { DomainUtil.normalizeRootDomain(pageUrl).orEmpty() }
        val displayName = WebRulesRegistry.current().domains.displayNames[normalizedSite].orEmpty()
        val baseTitle = inferCleanImageTitle(pageUrl, title, displayName)
        return deduped.mapIndexed { idx, imgUrl ->
            val ext = inferImageExtensionFromUrl(imgUrl, mimeType)
            ResultItem(
                id = 0,
                url = imgUrl,
                title = "$baseTitle (${idx + 1}/${deduped.size})",
                author = author.trim(),
                duration = "",
                thumb = imgUrl,
                website = normalizedSite,
                playlistTitle = baseTitle,
                playlistIndex = idx + 1,
                formats = arrayListOf(
                    Format(
                        format_id = IMAGE_ONLY_FORMAT_ID,
                        container = ext,
                        vcodec = "none",
                        acodec = "none",
                        format_note = "image_only",
                        url = imgUrl
                    )
                ),
                urls = "",
                chapters = ArrayList(),
                playlistURL = pageUrl
            )
        }
    }
}

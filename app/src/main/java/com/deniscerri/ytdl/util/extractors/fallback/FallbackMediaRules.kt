package com.deniscerri.ytdl.util.extractors.fallback

import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.util.BrowserMediaUtil
import com.deniscerri.ytdl.util.QualityCapsuleUtil
import com.deniscerri.ytdl.util.rules.FallbackScraperRules
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object FallbackMediaRules {

    private fun scraperRules(): FallbackScraperRules = WebRulesRegistry.current().fallbackScraper

    enum class StreamKind {
        PROGRESSIVE_MP4,
        PROGRESSIVE_WEBM,
        PROGRESSIVE_OTHER,
        HLS_MANIFEST,
        DASH_MANIFEST,
        AUDIO_ONLY
    }

    data class MediaCandidate(
        val url: String,
        val kind: StreamKind,
        val height: Int = 0,
        val bitrateKbps: Int = 0,
        val mimeType: String = "",
        val contentLength: Long = 0L,
        val headers: Map<String, String> = emptyMap(),
        val source: String = "",
        val isVideoOnly: Boolean = false,
        val hasAudio: Boolean = true
    )

    data class PageScrapeResult(
        val title: String = "",
        val thumbnail: String = "",
        val author: String = "",
        val candidates: List<MediaCandidate> = emptyList(),
        val imageUrls: List<String> = emptyList()
    )

    data class FallbackSelection(
        val primaryVideo: MediaCandidate? = null,
        val pairedAudio: MediaCandidate? = null,
        val audioOnly: MediaCandidate? = null
    )

    private val NON_MEDIA_EXTENSIONS = setOf(
        "js", "css", "png", "jpg", "jpeg", "webp", "gif", "svg", "ico",
        "woff", "woff2", "ttf", "otf", "eot", "vtt", "srt", "ass", "ssa",
        "json", "xml", "html", "htm", "php", "txt", "map"
    )

    private val SEGMENT_EXTENSIONS = setOf("ts", "m4s", "cmfv", "cmfa", "fmp4")

    private val SEGMENT_PATH_OR_QUERY_REGEX = Regex(
        """(/seg[-_]?\d+|chunk[-_]?\d+|frag[-_]?\d+|/init\.mp4|bytestart=|byteend=|[?&]range=)""",
        RegexOption.IGNORE_CASE
    )
    private val EFG_QUERY_REGEX = Regex("""(?:^|[?&])efg=([^&#]+)""", RegexOption.IGNORE_CASE)
    private val EFG_VENCODE_TAG_REGEX = Regex(""""vencode_tag"\s*:\s*"([^"]+)"""")
    private val EFG_HEIGHT_REGEX = Regex(
        """(?:^|[._-])(2160|1440|1080|720|480|360|240)(?:p)?(?=$|[._-])""",
        RegexOption.IGNORE_CASE
    )

    private data class MetaEfgHints(
        val isAudioOnly: Boolean = false,
        val isVideoOnly: Boolean = false,
        val heightHint: Int = 0
    )

    private val FORMAT_HEIGHT_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
    private val URL_P_HEIGHT_REGEX = Regex("""(?:^|[/_?&=\-.])(2160|1440|1080|720|480|360|240)p(?=$|[/_?&=\-.])""", RegexOption.IGNORE_CASE)
    private val URL_DIM_REGEX = Regex("""(\d{3,4})x(\d{3,4})""", RegexOption.IGNORE_CASE)
    private val DIGITS_REGEX = Regex("""(\d+)""")

    private val ALLOWED_FFMPEG_HEADER_KEYS = setOf(
        "cookie", "referer", "origin", "user-agent", "authorization"
    )

    private val ALLOWED_AUDIO_CONTAINERS = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav")
    private val ALLOWED_VIDEO_CONTAINERS = setOf("mp4", "mkv", "webm")

    fun classifyMediaCandidate(
        url: String,
        mimeType: String = "",
        headers: Map<String, String> = emptyMap(),
        source: String = "",
        contentLength: Long = 0L
    ): MediaCandidate? {
        val trimmedUrl = url.trim()
        val lowerUrl = trimmedUrl.lowercase(Locale.US)
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return null
        }

        val parsedUri = runCatching { URI(trimmedUrl) }.getOrNull()
        val host = parsedUri?.host?.lowercase(Locale.US).orEmpty()
        val path = parsedUri?.path?.lowercase(Locale.US) ?: lowerUrl.substringBefore('?').substringBefore('#')

        if (scraperRules().adOrTelemetryHostMarkers.any { host.contains(it.lowercase(Locale.US)) } ||
            lowerUrl.contains("facebook.com/tr")
        ) {
            return null
        }

        val lowerMime = mimeType.substringBefore(';').trim().lowercase(Locale.US)
        val mimeExplicitMedia = isExplicitMediaMime(lowerMime)

        val ext = extractExtension(path)
        if (ext in NON_MEDIA_EXTENSIONS && !mimeExplicitMedia) {
            return null
        }

        if (source != "newpipe") {
            if (ext in SEGMENT_EXTENSIONS || SEGMENT_PATH_OR_QUERY_REGEX.containsMatchIn(lowerUrl)) {
                return null
            }
        }

        val efgHints = parseMetaEfgHints(trimmedUrl)
        var kind = when {
            ext == "m3u8" || lowerMime.contains("mpegurl") -> StreamKind.HLS_MANIFEST
            ext == "mpd" || lowerMime.contains("dash+xml") -> StreamKind.DASH_MANIFEST
            ext in setOf("mp4", "m4v", "mov") ||
                lowerMime == "video/mp4" ||
                lowerMime == "video/quicktime" -> StreamKind.PROGRESSIVE_MP4
            ext == "webm" || lowerMime == "video/webm" -> StreamKind.PROGRESSIVE_WEBM
            ext in setOf("m4a", "mp3", "aac", "ogg", "opus", "flac", "wav") ||
                lowerMime.startsWith("audio/") -> StreamKind.AUDIO_ONLY
            ext in setOf("mkv", "flv") || lowerMime.startsWith("video/") -> StreamKind.PROGRESSIVE_OTHER
            else -> return null
        }

        if (efgHints?.isAudioOnly == true) {
            kind = StreamKind.AUDIO_ONLY
        }
        val isVideoOnly = kind != StreamKind.AUDIO_ONLY && efgHints?.isVideoOnly == true
        val height = if (kind == StreamKind.AUDIO_ONLY) {
            0
        } else {
            guessHeightFromUrl(trimmedUrl).takeIf { it > 0 } ?: (efgHints?.heightHint ?: 0)
        }
        return MediaCandidate(
            url = trimmedUrl,
            kind = kind,
            height = height,
            bitrateKbps = 0,
            mimeType = lowerMime,
            contentLength = contentLength.coerceAtLeast(0L),
            headers = headers,
            source = source,
            isVideoOnly = isVideoOnly,
            hasAudio = !isVideoOnly
        )
    }

    private fun parseMetaEfgHints(url: String): MetaEfgHints? {
        val rawEfg = EFG_QUERY_REGEX.find(url)?.groupValues?.getOrNull(1) ?: return null
        val urlDecoded = runCatching {
            URLDecoder.decode(rawEfg, StandardCharsets.UTF_8.name())
        }.getOrDefault(rawEfg)
        val decodedJson = decodeBase64Flexible(urlDecoded) ?: return null
        val vencodeTag = EFG_VENCODE_TAG_REGEX.find(decodedJson)?.groupValues?.getOrNull(1)
            ?.lowercase(Locale.US)
            ?: return null
        val isAudioOnly = vencodeTag.contains("audio")
        val isVideoOnly = !isAudioOnly && vencodeTag.contains("dash_") && !vencodeTag.contains("progressive")
        val heightHint = EFG_HEIGHT_REGEX.find(vencodeTag)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return MetaEfgHints(
            isAudioOnly = isAudioOnly,
            isVideoOnly = isVideoOnly,
            heightHint = heightHint
        )
    }

    private fun decodeBase64Flexible(input: String): String? {
        val cleaned = input.trim().replace('-', '+').replace('_', '/')
        if (cleaned.isEmpty()) return null
        val bytes = ByteArray((cleaned.length * 3) / 4 + 3)
        var outLen = 0
        var buffer = 0
        var bitsCollected = 0
        for (ch in cleaned) {
            val val6 = when (ch) {
                in 'A'..'Z' -> ch.code - 'A'.code
                in 'a'..'z' -> ch.code - 'a'.code + 26
                in '0'..'9' -> ch.code - '0'.code + 52
                '+' -> 62
                '/' -> 63
                '=' -> break
                else -> if (ch.isWhitespace()) continue else return null
            }
            buffer = (buffer shl 6) or val6
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                bytes[outLen++] = ((buffer shr bitsCollected) and 0xFF).toByte()
            }
        }
        if (outLen == 0) return null
        return String(bytes, 0, outLen, StandardCharsets.UTF_8)
    }

    private fun isExplicitMediaMime(lowerMime: String): Boolean {
        return lowerMime.startsWith("video/") ||
            lowerMime.startsWith("audio/") ||
            lowerMime.contains("mpegurl") ||
            lowerMime.contains("dash+xml")
    }

    private fun extractExtension(lowerPath: String): String {
        val lastSegment = lowerPath.substringAfterLast('/')
            .substringBefore('&')
            .substringBefore(';')
        val dotIdx = lastSegment.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx == lastSegment.length - 1) return ""
        return lastSegment.substring(dotIdx + 1)
    }

    fun guessHeightFromUrl(url: String): Int {
        val lower = url.lowercase(Locale.US)
        URL_P_HEIGHT_REGEX.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { h ->
            if (h > 0) return h
        }
        URL_DIM_REGEX.find(lower)?.let { match ->
            val w = match.groupValues[1].toIntOrNull() ?: 0
            val h = match.groupValues[2].toIntOrNull() ?: 0
            if (w >= 144 && h >= 144) {
                return minOf(w, h)
            }
        }
        if (lower.contains("4k") && (lower.contains("/4k") || lower.contains("_4k") || lower.contains("-4k"))) {
            return 2160
        }
        if (lower.contains("itag=22")) return 720
        if (lower.contains("itag=18")) return 360
        return 0
    }

    fun extractMediaFromHtml(
        html: String,
        pageUrl: String,
        defaultHeaders: Map<String, String> = emptyMap()
    ): PageScrapeResult {
        if (html.isBlank()) return PageScrapeResult()

        val title = extractMetaContent(html, "property", "og:title")
            ?: extractMetaContent(html, "name", "twitter:title")
            ?: extractTagInnerText(html, "title")
            ?: ""

        val rawThumbnail = resolveUrl(
            pageUrl,
            extractMetaContent(html, "property", "og:image:secure_url")
                ?: extractMetaContent(html, "property", "og:image")
                ?: extractMetaContent(html, "name", "twitter:image:src")
                ?: extractMetaContent(html, "name", "twitter:image")
        ).orEmpty()

        val author = extractMetaContent(html, "name", "author")
            ?: extractMetaContent(html, "property", "og:site_name")
            ?: ""

        val baseUri = runCatching { URI(pageUrl) }.getOrNull()
        val dedupMap = LinkedHashMap<String, MediaCandidate>()
        val imageSet = LinkedHashSet<String>()

        fun isAcceptableMetaImage(url: String): Boolean {
            if (BrowserMediaUtil.isGenericSiteImageUrl(url)) return false
            if (BrowserMediaUtil.isLikelyContentImageUrl(url)) return true
            val ext = extractExtension(url.substringBefore('?').substringBefore('#').lowercase(Locale.US))
            return ext !in NON_MEDIA_EXTENSIONS && ext !in SEGMENT_EXTENSIONS &&
                ext !in setOf("mp4", "m4v", "mov", "webm", "mkv", "flv", "m3u8", "mpd", "mp3", "m4a", "aac", "ogg", "opus")
        }

        fun registerJsonImage(rawUrl: String?) {
            val resolved = resolveUrl(baseUri, rawUrl) ?: return
            if (BrowserMediaUtil.isLikelyContentImageUrl(resolved) && !BrowserMediaUtil.isGenericSiteImageUrl(resolved)) {
                imageSet.add(resolved)
            }
        }

        // High-res embedded JSON photo URLs first
        val jsonImageKeys = listOf(
            "display_url",
            "image_url",
            "original_image_url",
            "full_picture",
            "media_url_https",
            "high_res_url"
        )
        for (key in jsonImageKeys) {
            val regex = Regex("""["']${Regex.escape(key)}["']\s*:\s*["']([^"']+)["']""")
            regex.findAll(html).forEach { match ->
                val rawValue = unescapeJsonUrl(match.groupValues[1])
                if (rawValue.startsWith("http", ignoreCase = true) || rawValue.startsWith("/")) {
                    registerJsonImage(rawValue)
                }
            }
        }

        // Meta tags & rel=image_src
        listOf("og:image:secure_url", "og:image").forEach { prop ->
            findAllMetaContents(html, "property", prop).forEach { raw ->
                resolveUrl(baseUri, raw)?.takeIf { isAcceptableMetaImage(it) }?.let { imageSet.add(it) }
            }
        }
        listOf("twitter:image:src", "twitter:image").forEach { prop ->
            findAllMetaContents(html, "name", prop).forEach { raw ->
                resolveUrl(baseUri, raw)?.takeIf { isAcceptableMetaImage(it) }?.let { imageSet.add(it) }
            }
        }
        listOf(
            Regex("""<link[^>]*\brel\s*=\s*["']image_src["'][^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<link[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*\brel\s*=\s*["']image_src["']""", RegexOption.IGNORE_CASE)
        ).forEach { linkRegex ->
            linkRegex.findAll(html).forEach { match ->
                val raw = unescapeHtml(match.groupValues[1]).trim()
                resolveUrl(baseUri, raw)?.takeIf { isAcceptableMetaImage(it) }?.let { imageSet.add(it) }
            }
        }

        val thumbnail = rawThumbnail.takeIf { it.isNotBlank() && !BrowserMediaUtil.isGenericSiteImageUrl(it) }
            ?: imageSet.firstOrNull().orEmpty()

        fun registerCandidate(rawUrl: String?, explicitMinHeight: Int = 0) {
            val resolved = resolveUrl(baseUri, rawUrl) ?: return
            val classified = classifyMediaCandidate(
                url = resolved,
                headers = defaultHeaders,
                source = "html_scrape"
            ) ?: return
            val adjusted = if (classified.height == 0 && explicitMinHeight > 0 && classified.kind != StreamKind.AUDIO_ONLY) {
                classified.copy(height = explicitMinHeight)
            } else {
                classified
            }
            val existing = dedupMap[adjusted.url]
            if (existing == null || adjusted.height > existing.height) {
                dedupMap[adjusted.url] = adjusted
            }
        }

        // 1. OpenGraph & Twitter meta tags
        listOf(
            "og:video:secure_url",
            "og:video:url",
            "og:video"
        ).forEach { prop ->
            findAllMetaContents(html, "property", prop).forEach { registerCandidate(it) }
        }
        findAllMetaContents(html, "name", "twitter:player:stream").forEach { registerCandidate(it) }

        // 2. <video src="..."> and <source src="..."> tags
        val mediaTagSrcRegex = Regex(
            """<(?:video|source|audio)\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        mediaTagSrcRegex.findAll(html).forEach { match ->
            registerCandidate(unescapeHtml(match.groupValues[1]))
        }

        // 3. Embedded JSON video URL keys (Instagram, Facebook, X, TikTok, Vimeo, etc.)
        for (rule in scraperRules().htmlJsonVideoKeys) {
            val key = rule.key
            val minHeight = rule.minHeight
            if (key.isBlank()) continue
            val regex = Regex("""["']${Regex.escape(key)}["']\s*:\s*["']([^"']+)["']""")
            regex.findAll(html).forEach { match ->
                val rawValue = unescapeJsonUrl(match.groupValues[1])
                if (rawValue.startsWith("http", ignoreCase = true) || rawValue.startsWith("/")) {
                    registerCandidate(rawValue, explicitMinHeight = minHeight)
                }
            }
        }

        return PageScrapeResult(
            title = unescapeHtml(title).trim(),
            thumbnail = thumbnail,
            author = unescapeHtml(author).trim(),
            candidates = dedupMap.values.toList(),
            imageUrls = imageSet.toList()
        )
    }

    private fun extractMetaContent(html: String, attrName: String, attrValue: String): String? {
        return findAllMetaContents(html, attrName, attrValue).firstOrNull()?.let { unescapeHtml(it).trim() }
    }

    private fun findAllMetaContents(html: String, attrName: String, attrValue: String): List<String> {
        val escapedVal = Regex.escape(attrValue)
        val p1 = Regex(
            """<meta[^>]*\b$attrName\s*=\s*["']$escapedVal["'][^>]*\bcontent\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val p2 = Regex(
            """<meta[^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*\b$attrName\s*=\s*["']$escapedVal["']""",
            RegexOption.IGNORE_CASE
        )
        val results = mutableListOf<String>()
        p1.findAll(html).forEach { results.add(unescapeHtml(it.groupValues[1]).trim()) }
        p2.findAll(html).forEach { results.add(unescapeHtml(it.groupValues[1]).trim()) }
        return results
    }

    private fun extractTagInnerText(html: String, tagName: String): String? {
        val r = Regex("""<$tagName\b[^>]*>(.*?)</$tagName>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return r.find(html)?.groupValues?.get(1)?.trim()
    }

    private fun resolveUrl(pageUrl: String, raw: String?): String? {
        val base = runCatching { URI(pageUrl) }.getOrNull()
        return resolveUrl(base, raw)
    }

    private fun resolveUrl(baseUri: URI?, raw: String?): String? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        val normalized = if (cleaned.startsWith("//")) "https:$cleaned" else cleaned
        if (normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) {
            return normalized
        }
        if (baseUri == null) return null
        return runCatching { baseUri.resolve(normalized).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun unescapeHtml(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun unescapeJsonUrl(input: String): String {
        return input
            .replace("""\\/""", "/")
            .replace("""\/""", "/")
            .replace("""\u0026""", "&")
            .replace("""\u003d""", "=")
            .replace("""\u003D""", "=")
            .replace("""\u002f""", "/")
            .replace("""\u002F""", "/")
            .replace("""\u0025""", "%")
            .replace("""\u003f""", "?")
            .replace("""\u003F""", "?")
            .replace("&amp;", "&")
            .trim()
    }

    fun parseRequestedMaxHeight(item: DownloadItem): Int {
        val fromFormat = QualityCapsuleUtil.resolveEffectiveResolutionHeight(item.format)
        if (fromFormat > 0) return fromFormat

        val match = FORMAT_HEIGHT_REGEX.find(item.format.format_id)
        val parsed = match?.groupValues?.get(1)?.toIntOrNull()
        if (parsed != null && parsed > 0) return parsed

        return 0
    }

    fun parseRequestedAudioBitrateKbps(item: DownloadItem): Int {
        val raw = item.audioPreferences.bitrate.trim()
        val parsed = DIGITS_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return parsed?.coerceIn(64, 320) ?: 192
    }

    fun resolveTargetAudioContainer(item: DownloadItem): String {
        val c = item.container.trim().lowercase(Locale.US)
        return if (c in ALLOWED_AUDIO_CONTAINERS) c else "mp3"
    }

    fun resolveTargetVideoContainer(item: DownloadItem): String {
        val c = item.container.trim().lowercase(Locale.US)
        return if (c in ALLOWED_VIDEO_CONTAINERS) c else "mp4"
    }

    fun deduplicateCandidates(candidates: List<MediaCandidate>): List<MediaCandidate> {
        val byUrl = LinkedHashMap<String, MediaCandidate>()
        for (c in candidates) {
            val key = c.url.trim()
            if (key.isEmpty()) continue
            val existing = byUrl[key]
            if (existing == null) {
                byUrl[key] = c
            } else {
                byUrl[key] = existing.copy(
                    height = maxOf(existing.height, c.height),
                    bitrateKbps = maxOf(existing.bitrateKbps, c.bitrateKbps),
                    mimeType = existing.mimeType.ifBlank { c.mimeType },
                    contentLength = maxOf(existing.contentLength, c.contentLength),
                    headers = existing.headers + c.headers,
                    source = existing.source.ifBlank { c.source },
                    isVideoOnly = existing.isVideoOnly && c.isVideoOnly,
                    hasAudio = existing.hasAudio || c.hasAudio
                )
            }
        }
        return byUrl.values.toList()
    }

    fun pickBestAudioCandidate(candidates: List<MediaCandidate>): MediaCandidate? {
        val audioOnly = deduplicateCandidates(candidates).filter { it.kind == StreamKind.AUDIO_ONLY }
        if (audioOnly.isEmpty()) return null
        return audioOnly.maxByOrNull { scoreAudioCandidate(it) }
    }

    private fun scoreAudioCandidate(c: MediaCandidate): Double {
        var score = 0.0
        val lowerUrl = c.url.lowercase(Locale.US)
        val lowerMime = c.mimeType.lowercase(Locale.US)
        if (lowerUrl.contains(".m4a") || lowerMime.contains("mp4") || lowerMime.contains("aac")) {
            score += 500.0
        } else if (lowerUrl.contains(".mp3") || lowerMime.contains("mpeg")) {
            score += 420.0
        } else if (lowerUrl.contains(".opus") || lowerMime.contains("opus")) {
            score += 380.0
        }
        score += c.bitrateKbps.coerceAtMost(512) * 2.0
        if (c.contentLength > 0L) {
            score += (c.contentLength.toDouble() / 1_000_000.0).coerceAtMost(100.0)
        }
        if (c.source == "newpipe") score += 100.0
        return score
    }

    fun rankVideoCandidates(
        candidates: List<MediaCandidate>,
        targetMaxHeight: Int = 0
    ): List<MediaCandidate> {
        val deduped = deduplicateCandidates(candidates)
        val bestAudio = pickBestAudioCandidate(deduped)
        val videoPool = deduped.filter { it.kind != StreamKind.AUDIO_ONLY }
        return videoPool.sortedByDescending {
            scoreVideoCandidate(it, targetMaxHeight, hasPairedAudio = bestAudio != null)
        }
    }

    private fun scoreVideoCandidate(
        c: MediaCandidate,
        targetMaxHeight: Int,
        hasPairedAudio: Boolean
    ): Double {
        if (c.isVideoOnly && !hasPairedAudio) {
            return -20_000.0 + c.height
        }

        var score = 0.0
        val lowerUrl = c.url.lowercase(Locale.US)

        if (targetMaxHeight > 0) {
            score += when {
                c.height in 1..targetMaxHeight -> 10_000.0 + c.height
                c.height > targetMaxHeight -> 5_000.0 - (c.height - targetMaxHeight)
                else -> 6_500.0
            }
        } else {
            score += if (c.height > 0) 6_000.0 + c.height else 3_000.0
        }

        score += when (c.kind) {
            StreamKind.PROGRESSIVE_MP4 -> if (!c.isVideoOnly && c.hasAudio) 800.0 else 750.0
            StreamKind.HLS_MANIFEST -> {
                var hlsScore = 650.0
                if (lowerUrl.contains("master.m3u8") || lowerUrl.contains("playlist.m3u8")) {
                    hlsScore += 120.0
                }
                hlsScore
            }
            StreamKind.PROGRESSIVE_WEBM -> 500.0
            StreamKind.DASH_MANIFEST -> 450.0
            StreamKind.PROGRESSIVE_OTHER -> 350.0
            StreamKind.AUDIO_ONLY -> -50_000.0
        }

        if (scraperRules().lowPriorityVideoMarkers.any { lowerUrl.contains(it.lowercase(Locale.US)) }) {
            score -= 5_000.0
        }
        if (lowerUrl.contains("hd")) {
            score += 40.0
        }
        if (c.source == "newpipe") {
            score += 150.0
        }
        if (c.bitrateKbps > 0) {
            score += (c.bitrateKbps * 0.05).coerceAtMost(150.0)
        }
        if (c.contentLength > 0L) {
            score += (c.contentLength.toDouble() / (1024.0 * 1024.0)).coerceAtMost(100.0) * 0.2
        }
        return score
    }

    fun selectBestForDownload(
        candidates: List<MediaCandidate>,
        type: DownloadType,
        targetMaxHeight: Int = 0
    ): FallbackSelection {
        val deduped = deduplicateCandidates(candidates)
        if (deduped.isEmpty()) return FallbackSelection()

        val bestAudio = pickBestAudioCandidate(deduped)
        val rankedVideos = rankVideoCandidates(deduped, targetMaxHeight)

        if (type == DownloadType.audio) {
            val chosen = bestAudio ?: rankedVideos.firstOrNull()
            return FallbackSelection(audioOnly = chosen)
        }

        val chosenVideo = rankedVideos.firstOrNull() ?: return FallbackSelection(audioOnly = bestAudio)
        val paired = if (chosenVideo.isVideoOnly) bestAudio else null
        return FallbackSelection(
            primaryVideo = chosenVideo,
            pairedAudio = paired
        )
    }

    fun sanitizeFilename(rawTitle: String, fallbackId: Long): String {
        val cleaned = rawTitle
            .replace(Regex("""[\\/:*?"<>|\r\n]"""), "_")
            .trim()
            .take(100)
        return cleaned.ifBlank { "media_$fallbackId" }
    }

    fun buildFfmpegHeaderArg(headers: Map<String, String>): String? {
        if (headers.isEmpty()) return null
        val filtered = headers.entries.mapNotNull { (k, v) ->
            val cleanKey = k.replace("\r", "").replace("\n", "").trim()
            val cleanVal = v.replace("\r", "").replace("\n", "").trim()
            if (cleanKey.lowercase(Locale.US) in ALLOWED_FFMPEG_HEADER_KEYS && cleanVal.isNotEmpty()) {
                "$cleanKey: $cleanVal"
            } else {
                null
            }
        }
        if (filtered.isEmpty()) return null
        return filtered.joinToString("\r\n") + "\r\n"
    }

    fun buildMuxVideoAudioArgs(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        audioCodecCopy: Boolean
    ): List<String> {
        val args = mutableListOf(
            "-y",
            "-i", videoPath,
            "-i", audioPath,
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-c:v", "copy",
            "-c:a", if (audioCodecCopy) "copy" else "aac",
            "-shortest"
        )
        if (outputPath.lowercase(Locale.US).endsWith(".mp4")) {
            args.addAll(listOf("-movflags", "+faststart"))
        }
        args.add(outputPath)
        return args
    }

    fun buildHlsOrDashDownloadArgs(
        manifestUrl: String,
        headers: Map<String, String>,
        outputPath: String,
        isAudioOnly: Boolean,
        audioContainer: String,
        audioBitrateKbps: Int,
        transcodeVideoToH264: Boolean = false
    ): List<String> {
        val args = mutableListOf(
            "-y",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "5"
        )
        buildFfmpegHeaderArg(headers)?.let {
            args.addAll(listOf("-headers", it))
        }
        args.addAll(listOf("-i", manifestUrl))

        if (isAudioOnly) {
            args.add("-vn")
            args.addAll(audioCodecArgs(audioContainer, audioBitrateKbps))
            args.add(outputPath)
            return args
        }

        val lowerOut = outputPath.lowercase(Locale.US)
        val isDash = manifestUrl.contains(".mpd", ignoreCase = true)
        if (!transcodeVideoToH264) {
            args.addAll(listOf("-c", "copy"))
            if (!isDash && lowerOut.endsWith(".mp4")) {
                args.addAll(listOf("-bsf:a", "aac_adtstoasc"))
            }
        } else {
            args.addAll(
                listOf(
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "23",
                    "-c:a", "aac",
                    "-b:a", "192k"
                )
            )
        }
        if (lowerOut.endsWith(".mp4")) {
            args.addAll(listOf("-movflags", "+faststart"))
        }
        args.add(outputPath)
        return args
    }

    fun buildAudioExtractArgs(
        inputPath: String,
        outputPath: String,
        audioContainer: String,
        audioBitrateKbps: Int
    ): List<String> {
        val args = mutableListOf("-y", "-i", inputPath, "-vn")
        args.addAll(audioCodecArgs(audioContainer, audioBitrateKbps))
        args.add(outputPath)
        return args
    }

    private fun audioCodecArgs(container: String, bitrateKbps: Int): List<String> {
        val kbps = bitrateKbps.coerceIn(64, 320)
        return when (container.lowercase(Locale.US)) {
            "m4a" -> listOf("-c:a", "aac", "-b:a", "${kbps}k")
            "opus", "ogg" -> listOf("-c:a", "libopus", "-b:a", "${kbps}k")
            "flac" -> listOf("-c:a", "flac")
            "wav" -> listOf("-c:a", "pcm_s16le")
            else -> listOf("-c:a", "libmp3lame", "-b:a", "${kbps}k")
        }
    }
}

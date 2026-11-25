package com.deniscerri.ytdl.util

import java.net.URI
import java.util.Locale

/**
 * Pure-Kotlin canonical URL/ID matcher that identifies whether a media URL on a
 * channel, profile, or listing page is already in History or currently in progress.
 */
object MediaHistoryMatcher {

    enum class MediaDownloadState {
        NEW,
        IN_PROGRESS,
        DOWNLOADED
    }

    enum class MediaTypeFilter {
        ALL,
        VIDEOS,
        IMAGES
    }

    fun matchesBatchFilters(
        isVideo: Boolean,
        state: MediaDownloadState,
        typeFilter: MediaTypeFilter,
        onlyNew: Boolean
    ): Boolean {
        if (onlyNew && state != MediaDownloadState.NEW) return false
        return when (typeFilter) {
            MediaTypeFilter.ALL -> true
            MediaTypeFilter.VIDEOS -> isVideo
            MediaTypeFilter.IMAGES -> !isVideo
        }
    }

    private val TRACKING_QUERY_PARAMS = setOf(
        "si", "pp", "feature", "t", "list", "index", "start_radio",
        "igsh", "igshid", "fbclid", "s", "ref", "ref_src", "ref_url",
        "sender_device", "is_from_webapp", "rdid", "share_url",
        "app", "attribution_link", "ab_channel", "context"
    )

    private val YT_VIDEO_ID_REGEX = Regex("""^[A-Za-z0-9_-]{8,15}$""")
    private val IG_PERMALINK_REGEX = Regex(
        """^(?:/[A-Za-z0-9._]+)?/(p|reel|reels|tv)/([A-Za-z0-9_-]+)(?:/.*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val TIKTOK_VIDEO_REGEX = Regex("""/(?:video|photo)/(\d+)""", RegexOption.IGNORE_CASE)
    private val TIKTOK_MOBILE_REGEX = Regex("""/v/(\d+)""", RegexOption.IGNORE_CASE)
    private val FB_MEDIA_REGEX = Regex("""/(?:reel|videos)/(\d+)""", RegexOption.IGNORE_CASE)
    private val X_STATUS_REGEX = Regex("""/status/(\d+)""", RegexOption.IGNORE_CASE)
    private val REDDIT_POST_REGEX = Regex("""/comments/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    fun extractCanonicalMediaKeys(rawUrl: String): Set<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return emptySet()
        val lowerPrefix = trimmed.lowercase(Locale.US)
        if (!lowerPrefix.startsWith("http://") && !lowerPrefix.startsWith("https://")) {
            return emptySet()
        }

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return setOf("url:$trimmed")
        val rawHost = uri.host?.lowercase(Locale.US)?.trim().orEmpty()
        if (rawHost.isEmpty()) return emptySet()

        val strippedHost = rawHost
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("mobile.")
            .removePrefix("web.")
        val rawPath = uri.rawPath.orEmpty()
        val lowerPath = rawPath.lowercase(Locale.US)
        val pathNoTrailing = rawPath.trimEnd('/').ifEmpty { "/" }

        val queryPairs = parseQueryPairs(uri.rawQuery.orEmpty())
        val significantQueryPairs = queryPairs.filter { (k, v) ->
            val lk = k.lowercase(Locale.US)
            lk.isNotEmpty() &&
                v.isNotEmpty() &&
                !lk.startsWith("utm_") &&
                lk !in TRACKING_QUERY_PARAMS
        }.sortedBy { it.first.lowercase(Locale.US) }

        val keys = linkedSetOf<String>()

        // YouTube (preserving exact case of video IDs!)
        if (strippedHost == "youtube.com" || strippedHost == "music.youtube.com" || strippedHost == "youtu.be") {
            val ytId = when {
                strippedHost == "youtu.be" -> {
                    pathNoTrailing.trimStart('/').substringBefore('/')
                }
                lowerPath == "/watch" -> {
                    queryPairs.firstOrNull { it.first.equals("v", ignoreCase = true) }?.second.orEmpty()
                }
                lowerPath.startsWith("/shorts/") -> {
                    rawPath.substringAfter("/shorts/", "").substringBefore('/')
                }
                lowerPath.startsWith("/live/") -> {
                    rawPath.substringAfter("/live/", "").substringBefore('/')
                }
                lowerPath.startsWith("/embed/") -> {
                    rawPath.substringAfter("/embed/", "").substringBefore('/')
                }
                else -> ""
            }.trim()
            if (ytId.isNotEmpty() && YT_VIDEO_ID_REGEX.matches(ytId)) {
                keys.add("yt:$ytId")
            }
        }

        // Instagram (preserving exact case of shortcodes!)
        if (strippedHost == "instagram.com" || strippedHost == "instagr.am") {
            val igMatch = IG_PERMALINK_REGEX.find(rawPath)
            val shortcode = igMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()
            if (shortcode.isNotEmpty()) {
                keys.add("ig:$shortcode")
            }
        }

        // TikTok
        if (strippedHost == "tiktok.com" || strippedHost.endsWith(".tiktok.com")) {
            val ttMatch = TIKTOK_VIDEO_REGEX.find(rawPath) ?: TIKTOK_MOBILE_REGEX.find(rawPath)
            val id = ttMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (id.isNotEmpty()) {
                keys.add("tt:$id")
            }
        }

        // Facebook
        if (strippedHost == "facebook.com" || strippedHost == "fb.watch") {
            val fbMatch = FB_MEDIA_REGEX.find(rawPath)
            val fbId = fbMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (lowerPath.startsWith("/watch")) {
                    queryPairs.firstOrNull { it.first.equals("v", ignoreCase = true) }?.second?.trim().orEmpty()
                } else {
                    ""
                }
            if (fbId.isNotEmpty() && fbId.all { it.isDigit() }) {
                keys.add("fb:$fbId")
            }
        }

        // X / Twitter
        if (strippedHost == "x.com" || strippedHost == "twitter.com") {
            val xId = X_STATUS_REGEX.find(rawPath)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (xId.isNotEmpty()) {
                keys.add("x:$xId")
            }
        }

        // Reddit
        if (strippedHost == "reddit.com" || strippedHost == "old.reddit.com" || strippedHost == "redd.it") {
            val rdId = REDDIT_POST_REGEX.find(rawPath)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (rdId.isNotEmpty()) {
                keys.add("rd:$rdId")
            }
        }

        // Normalized generic URL fallback key
        val querySuffix = if (significantQueryPairs.isEmpty()) {
            ""
        } else {
            "?" + significantQueryPairs.joinToString("&") { "${it.first}=${it.second}" }
        }
        val cleanGenericPath = if (keys.any { it.startsWith("ig:") }) {
            // Unify /user/reel/CODE and /reel/CODE under canonical path too
            val m = IG_PERMALINK_REGEX.find(rawPath)
            if (m != null) "/${m.groupValues[1].lowercase(Locale.US)}/${m.groupValues[2]}" else pathNoTrailing
        } else {
            pathNoTrailing
        }
        keys.add("url:$strippedHost$cleanGenericPath$querySuffix")

        return keys
    }

    fun primaryMediaKey(rawUrl: String): String {
        val keys = extractCanonicalMediaKeys(rawUrl)
        return keys.firstOrNull { !it.startsWith("url:") }
            ?: keys.firstOrNull()
            ?: rawUrl.trim()
    }

    private fun parseQueryPairs(rawQuery: String): List<Pair<String, String>> {
        if (rawQuery.isBlank()) return emptyList()
        return rawQuery.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val eqIdx = part.indexOf('=')
            if (eqIdx <= 0) return@mapNotNull null
            val k = part.substring(0, eqIdx).trim()
            val v = part.substring(eqIdx + 1).trim()
            if (k.isEmpty()) null else k to v
        }
    }

    class MediaHistoryIndex(
        downloadedUrls: Collection<String>,
        inProgressUrls: Collection<String>
    ) {
        private val downloadedKeys: Set<String> = buildSet {
            downloadedUrls.forEach { url ->
                addAll(extractCanonicalMediaKeys(url))
            }
        }

        private val inProgressKeys: Set<String> = buildSet {
            inProgressUrls.forEach { url ->
                addAll(extractCanonicalMediaKeys(url))
            }
        }

        fun getState(url: String): MediaDownloadState {
            val keys = extractCanonicalMediaKeys(url)
            if (keys.isEmpty()) return MediaDownloadState.NEW
            if (keys.any { it in inProgressKeys }) return MediaDownloadState.IN_PROGRESS
            if (keys.any { it in downloadedKeys }) return MediaDownloadState.DOWNLOADED
            return MediaDownloadState.NEW
        }

        fun matchedKeysFor(urls: Collection<String>): Pair<Set<String>, Set<String>> {
            val matchedDownloaded = linkedSetOf<String>()
            val matchedInProgress = linkedSetOf<String>()
            urls.forEach { url ->
                val keys = extractCanonicalMediaKeys(url)
                keys.forEach { key ->
                    if (key in inProgressKeys) matchedInProgress.add(key)
                    if (key in downloadedKeys) matchedDownloaded.add(key)
                }
            }
            return matchedDownloaded to matchedInProgress
        }
    }
}

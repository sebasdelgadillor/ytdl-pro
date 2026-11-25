package com.deniscerri.ytdl.util.rules

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Keep
data class QuickPlatformRule(
    @SerializedName("name") val name: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("showOnHome") val showOnHome: Boolean = false
)

@Keep
data class DomainRules(
    @SerializedName("multiPartPublicSuffixes") val multiPartPublicSuffixes: List<String> = emptyList(),
    @SerializedName("aliases") val aliases: Map<String, String> = emptyMap(),
    @SerializedName("displayNames") val displayNames: Map<String, String> = emptyMap(),
    @SerializedName("relatedCookieDomains") val relatedCookieDomains: Map<String, List<String>> = emptyMap(),
    @SerializedName("canonicalUrls") val canonicalUrls: Map<String, String> = emptyMap(),
    @SerializedName("cdnCookieOrigins") val cdnCookieOrigins: Map<String, String> = emptyMap(),
    @SerializedName("authCookieExact") val authCookieExact: List<String> = emptyList(),
    @SerializedName("authCookieSubstrings") val authCookieSubstrings: List<String> = emptyList()
)

@Keep
data class RateLimitPolicyRule(
    @SerializedName("minIntervalMs") val minIntervalMs: Long = 500L,
    @SerializedName("maxIntervalMs") val maxIntervalMs: Long = 1_200L,
    @SerializedName("ytdlpSleepRequestsSec") val ytdlpSleepRequestsSec: String = "",
    @SerializedName("maxRetries") val maxRetries: String = "3",
    @SerializedName("fragmentRetries") val fragmentRetries: String = "3",
    @SerializedName("retrySleepHttp") val retrySleepHttp: String = "http:exp=1:10",
    @SerializedName("retrySleepFragment") val retrySleepFragment: String = "fragment:exp=1:8"
)

@Keep
data class RateLimitRules(
    @SerializedName("strictDomains") val strictDomains: List<String> = emptyList(),
    @SerializedName("standardDomains") val standardDomains: List<String> = emptyList(),
    @SerializedName("strictPolicy") val strictPolicy: RateLimitPolicyRule? = null,
    @SerializedName("standardPolicy") val standardPolicy: RateLimitPolicyRule? = null,
    @SerializedName("defaultPolicy") val defaultPolicy: RateLimitPolicyRule? = null,
    @SerializedName("rateLimitErrorMarkers") val rateLimitErrorMarkers: List<String> = emptyList()
)

@Keep
data class MediaDetectionRules(
    @SerializedName("rejectedImageSubstrings") val rejectedImageSubstrings: List<String> = emptyList(),
    @SerializedName("trustedImageCdnMarkers") val trustedImageCdnMarkers: List<String> = emptyList(),
    @SerializedName("htmlPostUrlMarkers") val htmlPostUrlMarkers: List<String> = emptyList(),
    @SerializedName("videoPostUrlMarkers") val videoPostUrlMarkers: List<String> = emptyList(),
    @SerializedName("photoPageUrlMarkers") val photoPageUrlMarkers: List<String> = emptyList(),
    @SerializedName("feedPathMarkers") val feedPathMarkers: List<String> = emptyList(),
    @SerializedName("permalinkNoiseMarkers") val permalinkNoiseMarkers: List<String> = emptyList(),
    @SerializedName("highPriorityPermalinkMarkers") val highPriorityPermalinkMarkers: List<String> = emptyList(),
    @SerializedName("generalPostPermalinkMarkers") val generalPostPermalinkMarkers: List<String> = emptyList(),
    @SerializedName("videoHintKeywords") val videoHintKeywords: List<String> = emptyList(),
    @SerializedName("videoIframeHostMarkers") val videoIframeHostMarkers: List<String> = emptyList(),
    @SerializedName("uiNoiseSubstrings") val uiNoiseSubstrings: List<String> = emptyList(),
    @SerializedName("postContainerSelectors") val postContainerSelectors: List<String> = emptyList(),
    @SerializedName("dataAttributeNames") val dataAttributeNames: List<String> = emptyList(),
    @SerializedName("dataJsonVideoKeys") val dataJsonVideoKeys: List<String> = emptyList(),
    @SerializedName("dataJsonPermalinkKeys") val dataJsonPermalinkKeys: List<String> = emptyList(),
    @SerializedName("genericPlaceholderTitles") val genericPlaceholderTitles: List<String> = emptyList()
)

@Keep
data class HtmlJsonVideoKeyRule(
    @SerializedName("key") val key: String = "",
    @SerializedName("minHeight") val minHeight: Int = 0
)

@Keep
data class FallbackScraperRules(
    @SerializedName("adOrTelemetryHostMarkers") val adOrTelemetryHostMarkers: List<String> = emptyList(),
    @SerializedName("lowPriorityVideoMarkers") val lowPriorityVideoMarkers: List<String> = emptyList(),
    @SerializedName("htmlJsonVideoKeys") val htmlJsonVideoKeys: List<HtmlJsonVideoKeyRule> = emptyList()
)

@Keep
data class WebRulesConfig(
    @SerializedName("schemaVersion") val schemaVersion: Int = 1,
    @SerializedName("rulesVersion") val rulesVersion: String = "2026.11.25.2",
    @SerializedName("quickPlatforms") val quickPlatforms: List<QuickPlatformRule> = emptyList(),
    @SerializedName("domains") val domains: DomainRules = DomainRules(),
    @SerializedName("rateLimits") val rateLimits: RateLimitRules = RateLimitRules(),
    @SerializedName("mediaDetection") val mediaDetection: MediaDetectionRules = MediaDetectionRules(),
    @SerializedName("fallbackScraper") val fallbackScraper: FallbackScraperRules = FallbackScraperRules()
) {
    companion object {
        fun defaults(): WebRulesConfig = WebRulesConfig(
            schemaVersion = 1,
            rulesVersion = "2026.11.25.2",
            quickPlatforms = listOf(
                QuickPlatformRule("Instagram", "https://www.instagram.com/", true),
                QuickPlatformRule("Facebook", "https://www.facebook.com/", true),
                QuickPlatformRule("X", "https://x.com/", true),
                QuickPlatformRule("TikTok", "https://www.tiktok.com/", true),
                QuickPlatformRule("YouTube", "https://www.youtube.com/", true),
                QuickPlatformRule("Reddit", "https://www.reddit.com/", false),
                QuickPlatformRule("Pinterest", "https://www.pinterest.com/", false)
            ),
            domains = DomainRules(
                multiPartPublicSuffixes = listOf(
                    "co.uk", "org.uk", "ac.uk",
                    "com.br", "com.mx", "com.ar", "com.co",
                    "com.au", "net.au", "co.jp", "co.in",
                    "com.tr", "com.tw", "com.hk", "co.kr",
                    "com.es", "com.pe", "com.ve"
                ),
                aliases = mapOf(
                    "youtu.be" to "youtube.com",
                    "instagr.am" to "instagram.com",
                    "fb.watch" to "facebook.com",
                    "fb.com" to "facebook.com",
                    "fb.me" to "facebook.com",
                    "twitter.com" to "x.com",
                    "t.co" to "x.com",
                    "redd.it" to "reddit.com",
                    "pin.it" to "pinterest.com",
                    "b23.tv" to "bilibili.com",
                    "vk.ru" to "vk.com",
                    "snd.sc" to "soundcloud.com"
                ),
                displayNames = mapOf(
                    "instagram.com" to "Instagram",
                    "facebook.com" to "Facebook",
                    "x.com" to "X",
                    "tiktok.com" to "TikTok",
                    "youtube.com" to "YouTube",
                    "reddit.com" to "Reddit",
                    "pinterest.com" to "Pinterest",
                    "twitch.tv" to "Twitch",
                    "vimeo.com" to "Vimeo",
                    "dailymotion.com" to "Dailymotion",
                    "bilibili.com" to "Bilibili",
                    "soundcloud.com" to "SoundCloud",
                    "vk.com" to "VK"
                ),
                relatedCookieDomains = mapOf(
                    "x.com" to listOf("x.com", "twitter.com"),
                    "twitter.com" to listOf("x.com", "twitter.com"),
                    "youtube.com" to listOf("youtube.com", "google.com"),
                    "facebook.com" to listOf("facebook.com", "meta.com"),
                    "instagram.com" to listOf("instagram.com", "meta.com")
                ),
                canonicalUrls = mapOf(
                    "x.com" to "https://x.com/"
                ),
                cdnCookieOrigins = mapOf(
                    "fbcdn.net" to "https://www.facebook.com/",
                    "fbsbx.com" to "https://www.facebook.com/",
                    "cdninstagram.com" to "https://www.instagram.com/",
                    "twimg.com" to "https://x.com/"
                ),
                authCookieExact = listOf(
                    "sessionid", "ds_user_id", "c_user", "xs", "auth_token", "ct0", "twid",
                    "sid", "hsid", "ssid", "apisid", "sapisid", "login_info",
                    "__secure-1psid", "__secure-3psid", "sid_tt", "sessionid_ss", "uid_tt",
                    "reddit_session", "token_v2", "_pinterest_sess", "auth-token", "remixsid",
                    "sessdata", "bili_jct", "oauth_token", "connect.sid", "phpsessid",
                    "jsessionid", "remember_token", "logged_in", "access_token", "refresh_token",
                    "user_session", "session_id", "_session", "auth", "jwt"
                ),
                authCookieSubstrings = listOf(
                    "sessionid", "auth_token", "access_token", "refresh_token",
                    "user_session", "remember_token", "logged_in"
                )
            ),
            rateLimits = RateLimitRules(
                strictDomains = listOf(
                    "instagram.com", "facebook.com", "tiktok.com",
                    "x.com", "twitter.com", "threads.net"
                ),
                standardDomains = listOf(
                    "youtube.com", "reddit.com", "twitch.tv", "vimeo.com"
                ),
                strictPolicy = RateLimitPolicyRule(
                    minIntervalMs = 2_500L,
                    maxIntervalMs = 4_500L,
                    ytdlpSleepRequestsSec = "1.5",
                    maxRetries = "3",
                    fragmentRetries = "3",
                    retrySleepHttp = "http:exp=2:20",
                    retrySleepFragment = "fragment:exp=1:10"
                ),
                standardPolicy = RateLimitPolicyRule(
                    minIntervalMs = 1_000L,
                    maxIntervalMs = 2_000L,
                    ytdlpSleepRequestsSec = "1",
                    maxRetries = "3",
                    fragmentRetries = "3",
                    retrySleepHttp = "http:exp=2:15",
                    retrySleepFragment = "fragment:exp=1:10"
                ),
                defaultPolicy = RateLimitPolicyRule(
                    minIntervalMs = 500L,
                    maxIntervalMs = 1_200L,
                    ytdlpSleepRequestsSec = "",
                    maxRetries = "3",
                    fragmentRetries = "3",
                    retrySleepHttp = "http:exp=1:10",
                    retrySleepFragment = "fragment:exp=1:8"
                ),
                rateLimitErrorMarkers = listOf(
                    "429", "too many requests", "rate-limit", "rate limit",
                    "please wait a few minutes", "try again later"
                )
            ),
            mediaDetection = MediaDetectionRules(
                rejectedImageSubstrings = listOf(
                    "doubleclick", "googlesyndication", "google-analytics", "googletagmanager",
                    "facebook.com/tr", "static.xx.fbcdn.net/rsrc.php", "static.cdninstagram.com/rsrc.php",
                    "/emoji", "/sprites/", "favicon", "apple-touch-icon", "1x1", "blank.gif", "spacer.",
                    "p50x50", "p40x40", "s32x32", "s40x40", "s48x48", "s60x60", "s64x64", "s80x80",
                    "c0.0.40.40", "dst-jpg_p32x32", "dst-jpg_p40x40", "dst-jpg_p50x50",
                    "dst-jpg_p100x100", "dst-jpg_p160x160", "dst-jpg_s40x40", "dst-jpg_s60x60",
                    "dst-jpg_s150x150", "t51.2885-19", "s100x100", "s150x150", "p100x100",
                    "p160x160", "profile_pic"
                ),
                trustedImageCdnMarkers = listOf(
                    "scontent", "fbcdn.net", "cdninstagram.com", "i.redd.it",
                    "preview.redd.it", "i.pinimg.com", "media.tenor.com", "media.giphy.com"
                ),
                htmlPostUrlMarkers = listOf(
                    "/photo", "/p/", "/reel/", "/reels/", "/watch", "/status/",
                    "/posts/", "/videos/", "/video/", "/shorts/", "/clips/",
                    "/story.php", "fbid="
                ),
                videoPostUrlMarkers = listOf(
                    "/reel/", "/reels/", "/watch", "/video/", "/videos/",
                    "/shorts/", "/clips/", "fb.watch/", "youtu.be/", "vm.tiktok.com/",
                    "/share/v/", "/share/r/"
                ),
                photoPageUrlMarkers = listOf("/photo", "fbid="),
                feedPathMarkers = listOf(
                    "/home", "/feed", "/explore", "/reels",
                    "/following", "/search", "/foryou", "/watch",
                    "/videos", "/shorts", "/clips", "/discover", "/trending"
                ),
                permalinkNoiseMarkers = listOf(
                    "/login", "/signup", "/register", "/sharer", "/share.php",
                    "/help", "/policies", "/privacy", "/terms", "/hashtag/",
                    "/search", "/friends", "/messages", "/notifications",
                    "/settings", "/explore", "/home", "/feed",
                    "l.facebook.com/l.php", "l.instagram.com/",
                    "/reels/audio/", "/audio/", "/music/", "/sound/", "/sounds/",
                    "/accounts/", "/direct/", "/followers", "/following",
                    "/tagged", "/saved", "/places/", "/locations/",
                    "/effects/", "/ar/", "/stories/highlights/"
                ),
                highPriorityPermalinkMarkers = listOf(
                    "/reel/", "/reels/", "/watch", "/videos/", "/video/",
                    "/shorts/", "/clips/", "/photo", "fbid=", "/p/",
                    "/status/", "/share/v/", "/share/r/", "/share/p/"
                ),
                generalPostPermalinkMarkers = listOf(
                    "/posts/", "/permalink", "/story.php", "story_fbid=",
                    "multi_permalinks=", "/comments/", "/t/"
                ),
                videoHintKeywords = listOf(
                    "video", "reel", "clip", "reproductor", "reproducir",
                    "play video", "mute", "unmute", "silenciar", "sonido",
                    "activar audio", "watch"
                ),
                videoIframeHostMarkers = listOf(
                    "youtube.", "youtu.be", "vimeo.", "dailymotion.", "twitch.", "player."
                ),
                uiNoiseSubstrings = listOf(
                    "/rsrc.php", "/emoji", "/sprites/", "favicon", "blank.gif", "1x1",
                    "t51.2885-19", "s150x150", "p100x100", "p160x160", "profile_pic"
                ),
                postContainerSelectors = listOf(
                    "article",
                    "[role=\"article\"]",
                    "[data-testid=\"tweet\"]",
                    "[data-testid=\"post-container\"]",
                    "[data-ft]",
                    "[data-sigil*=\"story\"]",
                    "[data-tracking-duration-id]",
                    "[data-pagelet^=\"FeedUnit\"]"
                ),
                dataAttributeNames = listOf(
                    "data-store", "data-video-url", "data-permalink",
                    "data-url", "data-href", "data-src"
                ),
                dataJsonVideoKeys = listOf(
                    "src", "videoURL", "playable_url", "playable_url_quality_hd",
                    "sd_src", "hd_src", "video_url"
                ),
                dataJsonPermalinkKeys = listOf(
                    "permalink_url", "permalink", "share_url", "url"
                ),
                genericPlaceholderTitles = listOf(
                    "video player", "reproductor de video", "reproductor de vídeo",
                    "reproductor", "audio player", "media player", "player",
                    "instagram video player", "facebook video player",
                    "video", "reel", "reels", "watch",
                    "play", "play video", "pause",
                    "reproducir", "reproducir video", "reproducir vídeo",
                    "mute", "unmute", "silenciar", "activar audio", "activar sonido",
                    "sonido", "audio", "volume", "volumen",
                    "close", "cerrar", "share", "compartir",
                    "like", "me gusta", "comment", "comentar",
                    "follow", "seguir", "more", "more options", "más opciones",
                    "media",
                    "no photo description available", "no photo description available.",
                    "no hay ninguna descripción de la foto disponible",
                    "no hay ninguna descripción de la foto disponible."
                )
            ),
            fallbackScraper = FallbackScraperRules(
                adOrTelemetryHostMarkers = listOf(
                    "doubleclick.net", "googlesyndication.com", "google-analytics.com",
                    "googletagmanager.com", "adservice.", "analytics.", "pixel.", "sentry.io"
                ),
                lowPriorityVideoMarkers = listOf(
                    "preview", "poster", "thumb", "muted", "micro", "trailer"
                ),
                htmlJsonVideoKeys = listOf(
                    HtmlJsonVideoKeyRule("playable_url_quality_hd", 720),
                    HtmlJsonVideoKeyRule("browser_native_hd_url", 720),
                    HtmlJsonVideoKeyRule("hd_src", 720),
                    HtmlJsonVideoKeyRule("playable_url", 0),
                    HtmlJsonVideoKeyRule("browser_native_sd_url", 360),
                    HtmlJsonVideoKeyRule("sd_src", 360),
                    HtmlJsonVideoKeyRule("video_url", 0),
                    HtmlJsonVideoKeyRule("contentUrl", 0),
                    HtmlJsonVideoKeyRule("hls_manifest_url", 0),
                    HtmlJsonVideoKeyRule("playbackUrl", 0),
                    HtmlJsonVideoKeyRule("dash_manifest", 0)
                )
            )
        )
    }
}

@Keep
data class BrowserJsInspectorConfig(
    @SerializedName("uiNoiseSubstrings") val uiNoiseSubstrings: List<String>,
    @SerializedName("feedPathMarkers") val feedPathMarkers: List<String>,
    @SerializedName("permalinkNoiseMarkers") val permalinkNoiseMarkers: List<String>,
    @SerializedName("highPriorityPermalinkMarkers") val highPriorityPermalinkMarkers: List<String>,
    @SerializedName("generalPostPermalinkMarkers") val generalPostPermalinkMarkers: List<String>,
    @SerializedName("videoHintKeywords") val videoHintKeywords: List<String>,
    @SerializedName("videoIframeHostMarkers") val videoIframeHostMarkers: List<String>,
    @SerializedName("postContainerSelectors") val postContainerSelectors: List<String>,
    @SerializedName("dataAttributeNames") val dataAttributeNames: List<String>,
    @SerializedName("dataJsonVideoKeys") val dataJsonVideoKeys: List<String>,
    @SerializedName("dataJsonPermalinkKeys") val dataJsonPermalinkKeys: List<String>,
    @SerializedName("genericPlaceholderTitles") val genericPlaceholderTitles: List<String>
)

object WebRulesRegistry {
    private const val ASSET_FILE_NAME = "web_rules.json"
    private const val OVERRIDE_RELATIVE_PATH = "rules/web_rules_override.json"

    private val compactGson: Gson = Gson()
    private val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var activeConfig: WebRulesConfig = WebRulesConfig.defaults()

    @Volatile
    private var usingOverride: Boolean = false

    fun current(): WebRulesConfig = activeConfig

    fun isUsingOverride(): Boolean = usingOverride

    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        val bundled = loadBundledConfig(appCtx) ?: WebRulesConfig.defaults()
        val overrideFile = overrideFile(appCtx)
        if (overrideFile.exists() && overrideFile.length() > 0L) {
            val rawOverride = runCatching { overrideFile.readText(Charsets.UTF_8) }.getOrNull()
            if (!rawOverride.isNullOrBlank()) {
                val parsed = parseJson(rawOverride, base = bundled)
                if (parsed.isSuccess) {
                    activeConfig = parsed.getOrThrow()
                    usingOverride = true
                    return
                }
            }
        }
        activeConfig = bundled
        usingOverride = false
    }

    private fun loadBundledConfig(context: Context): WebRulesConfig? {
        return runCatching {
            val raw = context.assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseJson(raw, WebRulesConfig.defaults()).getOrNull()
        }.getOrNull()
    }

    fun parseJson(rawJson: String, base: WebRulesConfig = WebRulesConfig.defaults()): Result<WebRulesConfig> {
        return runCatching {
            val trimmed = rawJson.trim()
            require(trimmed.isNotEmpty()) { "Empty rules JSON" }
            val elem = JsonParser.parseString(trimmed)
            require(elem.isJsonObject) { "Rules JSON root must be an object" }
            val parsed = compactGson.fromJson(elem, WebRulesConfig::class.java)
                ?: throw IllegalArgumentException("Failed to deserialize WebRulesConfig")
            mergeWithBase(parsed, base)
        }
    }

    fun applyConfig(config: WebRulesConfig, isOverride: Boolean = false) {
        activeConfig = config
        usingOverride = isOverride
    }

    fun resetToDefaults() {
        activeConfig = WebRulesConfig.defaults()
        usingOverride = false
    }

    fun importOverrideJson(context: Context, rawJson: String): Result<WebRulesConfig> {
        val appCtx = context.applicationContext ?: context
        val base = loadBundledConfig(appCtx) ?: WebRulesConfig.defaults()
        val parsed = parseJson(rawJson, base = base)
        if (parsed.isFailure) return parsed

        val config = parsed.getOrThrow()
        val writeRes = runCatching {
            val target = overrideFile(appCtx)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(prettyGson.toJson(config), Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        return if (writeRes.isSuccess) {
            activeConfig = config
            usingOverride = true
            Result.success(config)
        } else {
            Result.failure(writeRes.exceptionOrNull() ?: IllegalStateException("Failed to save override"))
        }
    }

    fun resetOverride(context: Context) {
        val appCtx = context.applicationContext ?: context
        runCatching {
            val target = overrideFile(appCtx)
            if (target.exists()) target.delete()
        }
        activeConfig = loadBundledConfig(appCtx) ?: WebRulesConfig.defaults()
        usingOverride = false
    }

    const val REMOTE_RULES_URL =
        "https://raw.githubusercontent.com/sebasdelgadillor/ytdl-pro/main/app/src/main/assets/web_rules.json"

    private const val MAX_REMOTE_RULES_BYTES = 512 * 1024L

    private val remoteHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun isRemoteVersionNewer(currentVersion: String, remoteVersion: String): Boolean {
        val cur = currentVersion.trim()
        val rem = remoteVersion.trim()
        if (rem.isEmpty()) return false
        if (rem == cur) return false

        fun numericParts(ver: String): List<Int>? {
            val prefix = ver.substringBefore('-').substringBefore(' ').trim()
            if (prefix.isEmpty()) return null
            val parts = prefix.split('.')
            val parsed = parts.map { it.toIntOrNull() }
            if (parsed.any { it == null }) return null
            return parsed.filterNotNull()
        }

        val curParts = numericParts(cur)
        val remParts = numericParts(rem)
        if (curParts != null && remParts != null) {
            val maxLen = maxOf(curParts.size, remParts.size)
            for (i in 0 until maxLen) {
                val c = curParts.getOrElse(i) { 0 }
                val r = remParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }
        return true
    }

    suspend fun fetchAndApplyRemoteRules(
        context: Context,
        url: String = REMOTE_RULES_URL
    ): Result<WebRulesConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "YTDLPro-RulesUpdater/1.0")
                .header("Accept", "application/json")
                .build()

            val body = remoteHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                val contentLen = resp.body.contentLength()
                if (contentLen > MAX_REMOTE_RULES_BYTES) {
                    throw IllegalStateException("Remote rules file exceeds limit")
                }
                val text = resp.body.string()
                if (text.toByteArray(Charsets.UTF_8).size > MAX_REMOTE_RULES_BYTES) {
                    throw IllegalStateException("Remote rules payload exceeds limit")
                }
                text
            }
            importOverrideJson(context, body).getOrThrow()
        }
    }

    fun exportCurrentJson(): String = prettyGson.toJson(activeConfig)

    fun toBrowserJsConfigJson(): String {
        val m = activeConfig.mediaDetection
        val dto = BrowserJsInspectorConfig(
            uiNoiseSubstrings = m.uiNoiseSubstrings,
            feedPathMarkers = m.feedPathMarkers,
            permalinkNoiseMarkers = m.permalinkNoiseMarkers,
            highPriorityPermalinkMarkers = m.highPriorityPermalinkMarkers,
            generalPostPermalinkMarkers = m.generalPostPermalinkMarkers,
            videoHintKeywords = m.videoHintKeywords,
            videoIframeHostMarkers = m.videoIframeHostMarkers,
            postContainerSelectors = m.postContainerSelectors,
            dataAttributeNames = m.dataAttributeNames,
            dataJsonVideoKeys = m.dataJsonVideoKeys,
            dataJsonPermalinkKeys = m.dataJsonPermalinkKeys,
            genericPlaceholderTitles = m.genericPlaceholderTitles
        )
        return compactGson.toJson(dto)
    }

    private fun overrideFile(context: Context): File = File(context.filesDir, OVERRIDE_RELATIVE_PATH)

    private fun mergeWithBase(incoming: WebRulesConfig, base: WebRulesConfig): WebRulesConfig {
        val inDom = incoming.domains
        val baseDom = base.domains
        val mergedDomains = DomainRules(
            multiPartPublicSuffixes = inDom.multiPartPublicSuffixes.ifEmpty { baseDom.multiPartPublicSuffixes },
            aliases = baseDom.aliases + inDom.aliases,
            displayNames = baseDom.displayNames + inDom.displayNames,
            relatedCookieDomains = baseDom.relatedCookieDomains + inDom.relatedCookieDomains,
            canonicalUrls = baseDom.canonicalUrls + inDom.canonicalUrls,
            cdnCookieOrigins = baseDom.cdnCookieOrigins + inDom.cdnCookieOrigins,
            authCookieExact = inDom.authCookieExact.ifEmpty { baseDom.authCookieExact },
            authCookieSubstrings = inDom.authCookieSubstrings.ifEmpty { baseDom.authCookieSubstrings }
        )

        val inRl = incoming.rateLimits
        val baseRl = base.rateLimits
        val mergedRateLimits = RateLimitRules(
            strictDomains = inRl.strictDomains.ifEmpty { baseRl.strictDomains },
            standardDomains = inRl.standardDomains.ifEmpty { baseRl.standardDomains },
            strictPolicy = inRl.strictPolicy ?: baseRl.strictPolicy,
            standardPolicy = inRl.standardPolicy ?: baseRl.standardPolicy,
            defaultPolicy = inRl.defaultPolicy ?: baseRl.defaultPolicy,
            rateLimitErrorMarkers = inRl.rateLimitErrorMarkers.ifEmpty { baseRl.rateLimitErrorMarkers }
        )

        val inMd = incoming.mediaDetection
        val baseMd = base.mediaDetection
        val mergedMedia = MediaDetectionRules(
            rejectedImageSubstrings = inMd.rejectedImageSubstrings.ifEmpty { baseMd.rejectedImageSubstrings },
            trustedImageCdnMarkers = inMd.trustedImageCdnMarkers.ifEmpty { baseMd.trustedImageCdnMarkers },
            htmlPostUrlMarkers = inMd.htmlPostUrlMarkers.ifEmpty { baseMd.htmlPostUrlMarkers },
            videoPostUrlMarkers = inMd.videoPostUrlMarkers.ifEmpty { baseMd.videoPostUrlMarkers },
            photoPageUrlMarkers = inMd.photoPageUrlMarkers.ifEmpty { baseMd.photoPageUrlMarkers },
            feedPathMarkers = inMd.feedPathMarkers.ifEmpty { baseMd.feedPathMarkers },
            permalinkNoiseMarkers = inMd.permalinkNoiseMarkers.ifEmpty { baseMd.permalinkNoiseMarkers },
            highPriorityPermalinkMarkers = inMd.highPriorityPermalinkMarkers.ifEmpty { baseMd.highPriorityPermalinkMarkers },
            generalPostPermalinkMarkers = inMd.generalPostPermalinkMarkers.ifEmpty { baseMd.generalPostPermalinkMarkers },
            videoHintKeywords = inMd.videoHintKeywords.ifEmpty { baseMd.videoHintKeywords },
            videoIframeHostMarkers = inMd.videoIframeHostMarkers.ifEmpty { baseMd.videoIframeHostMarkers },
            uiNoiseSubstrings = inMd.uiNoiseSubstrings.ifEmpty { baseMd.uiNoiseSubstrings },
            postContainerSelectors = inMd.postContainerSelectors.ifEmpty { baseMd.postContainerSelectors },
            dataAttributeNames = inMd.dataAttributeNames.ifEmpty { baseMd.dataAttributeNames },
            dataJsonVideoKeys = inMd.dataJsonVideoKeys.ifEmpty { baseMd.dataJsonVideoKeys },
            dataJsonPermalinkKeys = inMd.dataJsonPermalinkKeys.ifEmpty { baseMd.dataJsonPermalinkKeys },
            genericPlaceholderTitles = inMd.genericPlaceholderTitles.ifEmpty { baseMd.genericPlaceholderTitles }
        )

        val inFb = incoming.fallbackScraper
        val baseFb = base.fallbackScraper
        val mergedFallback = FallbackScraperRules(
            adOrTelemetryHostMarkers = inFb.adOrTelemetryHostMarkers.ifEmpty { baseFb.adOrTelemetryHostMarkers },
            lowPriorityVideoMarkers = inFb.lowPriorityVideoMarkers.ifEmpty { baseFb.lowPriorityVideoMarkers },
            htmlJsonVideoKeys = inFb.htmlJsonVideoKeys.ifEmpty { baseFb.htmlJsonVideoKeys }
        )

        return WebRulesConfig(
            schemaVersion = incoming.schemaVersion.takeIf { it > 0 } ?: base.schemaVersion,
            rulesVersion = incoming.rulesVersion.ifBlank { base.rulesVersion },
            quickPlatforms = incoming.quickPlatforms.filter { it.name.isNotBlank() && it.url.startsWith("http") }
                .ifEmpty { base.quickPlatforms },
            domains = mergedDomains,
            rateLimits = mergedRateLimits,
            mediaDetection = mergedMedia,
            fallbackScraper = mergedFallback
        )
    }
}

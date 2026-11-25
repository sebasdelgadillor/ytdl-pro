package com.deniscerri.ytdl.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.util.extractors.fallback.FallbackMediaRules
import com.deniscerri.ytdl.util.extractors.fallback.HeadlessWebViewSniffer
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

object ImageDownloadHelper {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"

    private val DIRECT_IMAGE_REGEX =
        Regex("""\.(jpe?g|png|webp|gif|bmp|avif)(\?.*)?$""", RegexOption.IGNORE_CASE)

    fun isLikelyImageUrl(url: String): Boolean {
        return DIRECT_IMAGE_REGEX.containsMatchIn(url) || BrowserMediaUtil.isLikelyContentImageUrl(url)
    }

    private fun decodeDataImageBytes(dataUrl: String): ByteArray? {
        if (!dataUrl.startsWith("data:image/", ignoreCase = true)) return null
        val commaIdx = dataUrl.indexOf(',')
        if (commaIdx <= 0 || commaIdx >= dataUrl.length - 1) return null
        val payload = dataUrl.substring(commaIdx + 1)
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun resolveAuthCookieForImage(targetUrl: String, refererUrl: String): String? {
        val direct = SiteSessionManager.getCookieHeaderForUrl(targetUrl)
        if (!direct.isNullOrBlank()) return direct
        if (refererUrl.startsWith("http")) {
            SiteSessionManager.getCookieHeaderForUrl(refererUrl)?.let { return it }
        }
        val lower = targetUrl.lowercase(Locale.US)
        val cdnOrigins = WebRulesRegistry.current().domains.cdnCookieOrigins
        for ((marker, originUrl) in cdnOrigins) {
            if (lower.contains(marker.lowercase(Locale.US))) {
                SiteSessionManager.getCookieHeaderForUrl(originUrl)?.let { return it }
            }
        }
        return null
    }

    private fun downloadBytesWithAuth(
        context: Context,
        imageUrl: String,
        refererUrl: String
    ): ByteArray? {
        decodeDataImageBytes(imageUrl)?.let { return it }
        if (!imageUrl.startsWith("http", ignoreCase = true)) return null

        val preferredUa = SiteSessionManager.getPreferredUserAgentForUrl(
            context,
            refererUrl.takeIf { it.startsWith("http") } ?: imageUrl
        ) ?: USER_AGENT

        val req = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", preferredUa)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .apply {
                if (refererUrl.startsWith("http")) {
                    header("Referer", refererUrl)
                }
                resolveAuthCookieForImage(imageUrl, refererUrl)?.let { header("Cookie", it) }
            }
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val ct = resp.header("Content-Type").orEmpty().lowercase(Locale.US)
                if (ct.startsWith("text/html")) return@use null
                resp.body.bytes().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    suspend fun fetchPreviewBitmap(
        context: Context,
        imageUrl: String,
        refererUrl: String = ""
    ): Bitmap? = withContext(Dispatchers.IO) {
        val rawBytes = downloadBytesWithAuth(context, imageUrl, refererUrl) ?: return@withContext null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
        val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        var sample = 1
        while (maxDim / sample > 1080) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        runCatching {
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
        }.getOrNull()
    }

    private val META_TAG_PATTERNS = listOf(
        Regex("""<meta[^>]+property=["']og:image(?::secure_url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image(?::secure_url)?["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+name=["']twitter:image(?::src)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']twitter:image(?::src)?["']""", RegexOption.IGNORE_CASE),
        Regex("""<link[^>]+rel=["']image_src["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun extractImageCandidatesFromPage(
        url: String,
        context: Context? = null
    ): List<String> = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext emptyList()
        if (DIRECT_IMAGE_REGEX.containsMatchIn(url)) {
            return@withContext listOf(url)
        }

        val preferredUa = context?.let {
            SiteSessionManager.getPreferredUserAgentForUrl(it, url)
        } ?: USER_AGENT

        val html = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", preferredUa)
                .apply {
                    SiteSessionManager.getCookieHeaderForUrl(url)?.let { header("Cookie", it) }
                }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val ct = resp.header("Content-Type").orEmpty().lowercase(Locale.US)
                if (ct.startsWith("image/")) {
                    return@withContext listOf(url)
                }
                resp.body.string()
            }
        }.getOrDefault("")

        if (html.isBlank()) {
            if (context != null) {
                val sniffed = HeadlessWebViewSniffer.sniff(
                    context = context,
                    pageUrl = url,
                    timeoutMs = 10_000L,
                    settleAfterFirstMediaMs = 1_800L
                )
                return@withContext sniffed.imageUrls.filterNot { BrowserMediaUtil.isGenericSiteImageUrl(it) }
            }
            return@withContext emptyList()
        }

        val found = linkedSetOf<String>()
        val scraped = FallbackMediaRules.extractMediaFromHtml(html, url)
        scraped.imageUrls
            .filterNot { BrowserMediaUtil.isGenericSiteImageUrl(it) }
            .forEach { found.add(it) }

        val baseUri = runCatching { URI(url) }.getOrNull()
        for (pattern in META_TAG_PATTERNS) {
            pattern.findAll(html).forEach { match ->
                val raw = match.groupValues[1]
                    .replace("&amp;", "&")
                    .trim()
                if (raw.isNotEmpty()) {
                    val resolved = if (raw.startsWith("http")) {
                        raw
                    } else {
                        runCatching { baseUri?.resolve(raw)?.toString() }.getOrNull().orEmpty()
                    }
                    if (resolved.startsWith("http") && !BrowserMediaUtil.isGenericSiteImageUrl(resolved)) {
                        found.add(resolved)
                    }
                }
            }
        }
        if (found.isEmpty() && context != null) {
            val sniffed = HeadlessWebViewSniffer.sniff(
                context = context,
                pageUrl = url,
                timeoutMs = 10_000L,
                settleAfterFirstMediaMs = 1_800L
            )
            sniffed.imageUrls
                .filterNot { BrowserMediaUtil.isGenericSiteImageUrl(it) }
                .forEach { found.add(it) }
        }
        found.toList()
    }

    suspend fun downloadImage(
        context: Context,
        item: DownloadItem,
        tempFileDir: File,
        onProgress: (Int, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(10, "Resolving image URL...")
            val candidateUrls = linkedSetOf<String>()
            fun acceptPreseededUrl(raw: String?): String? {
                val u = raw?.trim().orEmpty()
                if (u.isEmpty()) return null
                if (u.startsWith("data:image/", ignoreCase = true)) return u
                if (!u.startsWith("http", ignoreCase = true)) return null
                if (BrowserMediaUtil.isGenericSiteImageUrl(u)) return null
                if (isLikelyImageUrl(u)) return u
                if (u != item.url && !BrowserMediaUtil.isContentPermalinkUrl(u) && !BrowserMediaUtil.isFeedOrListingUrl(u)) {
                    return u
                }
                return null
            }
            acceptPreseededUrl(item.format.url)?.let { candidateUrls.add(it) }
            acceptPreseededUrl(item.thumb)?.let { candidateUrls.add(it) }
            if (item.url.startsWith("http")) {
                if (isLikelyImageUrl(item.url) && !BrowserMediaUtil.isGenericSiteImageUrl(item.url)) {
                    candidateUrls.add(item.url)
                } else if (candidateUrls.isEmpty()) {
                    extractImageCandidatesFromPage(item.url, context).forEach { candidateUrls.add(it) }
                }
            } else if (item.url.startsWith("data:image/", ignoreCase = true)) {
                candidateUrls.add(item.url)
            }

            if (candidateUrls.isEmpty() && item.url.startsWith("http")) {
                extractImageCandidatesFromPage(item.url, context).forEach { candidateUrls.add(it) }
            }

            if (candidateUrls.isEmpty()) {
                throw IllegalStateException("No downloadable image URL found")
            }

            onProgress(35, "Downloading image...")
            var rawBytes: ByteArray? = null
            for (candidate in candidateUrls) {
                rawBytes = downloadBytesWithAuth(context, candidate, item.url)
                if (rawBytes != null) break
            }
            if (rawBytes == null && item.url.startsWith("http")) {
                val extraCandidates = extractImageCandidatesFromPage(item.url, context)
                for (candidate in extraCandidates) {
                    if (candidate in candidateUrls) continue
                    rawBytes = downloadBytesWithAuth(context, candidate, item.url)
                    if (rawBytes != null) break
                }
            }
            val finalBytes = rawBytes ?: throw IllegalStateException("Unable to download image bytes")

            onProgress(75, "Converting image...")
            val targetExt = item.container.lowercase(Locale.US)
                .takeIf { it in listOf("jpg", "png", "webp") } ?: "jpg"

            val safeName = item.title.ifBlank { "image_${item.id}" }
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .trim()
                .ifBlank { "image_${item.id}" }
                .take(100)

            tempFileDir.mkdirs()
            val outFile = File(tempFileDir, "$safeName.$targetExt")

            val decoded = BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size)
            if (decoded != null) {
                try {
                    val encodedBytes = ByteArrayOutputStream().use { baos ->
                        val format = when (targetExt) {
                            "png" -> Bitmap.CompressFormat.PNG
                            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Bitmap.CompressFormat.WEBP_LOSSY
                            } else {
                                @Suppress("DEPRECATION")
                                Bitmap.CompressFormat.WEBP
                            }
                            else -> Bitmap.CompressFormat.JPEG
                        }
                        val quality = if (targetExt == "png") 100 else 95
                        if (!decoded.compress(format, quality, baos)) {
                            throw IllegalStateException("Failed to encode image as $targetExt")
                        }
                        baos.toByteArray()
                    }
                    outFile.writeBytes(encodedBytes)
                } finally {
                    decoded.recycle()
                }
            } else {
                outFile.writeBytes(finalBytes)
            }

            onProgress(100, "Image ready")
            outFile
        }
    }
}

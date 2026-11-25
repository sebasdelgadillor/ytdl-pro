package com.deniscerri.ytdl.util.extractors.fallback

import android.content.Context
import android.util.Log
import com.deniscerri.ytdl.App
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.util.BrowserMediaUtil
import com.deniscerri.ytdl.util.DomainUtil
import com.deniscerri.ytdl.util.Extensions.toStringDuration
import com.deniscerri.ytdl.util.SiteSessionManager
import com.deniscerri.ytdl.util.extractors.fallback.FallbackMediaRules.MediaCandidate
import com.deniscerri.ytdl.util.extractors.fallback.FallbackMediaRules.StreamKind
import com.deniscerri.ytdl.util.extractors.newpipe.NewPipeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

object LocalFallbackEngine {

    private const val TAG = "LocalFallbackEngine"

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class ExtractedFallbackInfo(
        val url: String,
        val title: String = "",
        val author: String = "",
        val durationSeconds: Long = 0L,
        val thumbnail: String = "",
        val website: String = "",
        val candidates: List<MediaCandidate> = emptyList(),
        val imageUrls: List<String> = emptyList(),
        val imageMimeType: String = "",
        val sourceEngine: String = ""
    )

    data class FallbackDownloadResult(
        val outputFile: File,
        val engineUsed: String,
        val resolvedTitle: String?,
        val resolvedAuthor: String?,
        val resolvedThumb: String?
    )

    private data class ExecutionPlan(
        val primary: MediaCandidate,
        val pairedAudio: MediaCandidate?,
        val isAudioMode: Boolean
    )

    private val initLock = Any()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun ensureNewPipeInitialized(context: Context) {
        if (runCatching { NewPipe.getDownloader() }.getOrNull() != null) return
        synchronized(initLock) {
            if (runCatching { NewPipe.getDownloader() }.getOrNull() == null) {
                NewPipeUtil(context.applicationContext)
            }
        }
    }

    suspend fun extractFromNewPipe(context: Context, url: String): ExtractedFallbackInfo? =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                return@withContext null
            }
            ensureNewPipeInitialized(context)
            val service = runCatching { NewPipe.getServiceByUrl(url) }.getOrNull() ?: return@withContext null

            val info = try {
                StreamInfo.getInfo(service, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "NewPipe extraction failed for $url: ${e.message}")
                return@withContext null
            }

            val candidates = mutableListOf<MediaCandidate>()
            val baseHeaders = buildPageRequestHeaders(url)

            info.videoStreams.orEmpty().forEach { s ->
                val streamUrl = if (s.isUrl) s.content else ""
                if (streamUrl.startsWith("http", ignoreCase = true)) {
                    val kind = mapVideoStreamKind(s.deliveryMethod, s.format, streamUrl)
                    val height = resolveNewPipeVideoHeight(s.height, s.resolution)
                    candidates.add(
                        MediaCandidate(
                            url = streamUrl,
                            kind = kind,
                            height = height,
                            bitrateKbps = (s.bitrate / 1000).coerceAtLeast(0),
                            mimeType = s.format?.mimeType.orEmpty(),
                            contentLength = s.itagItem?.contentLength?.coerceAtLeast(0L) ?: 0L,
                            headers = baseHeaders,
                            source = "newpipe",
                            isVideoOnly = false,
                            hasAudio = true
                        )
                    )
                }
            }

            info.videoOnlyStreams.orEmpty().forEach { s ->
                val streamUrl = if (s.isUrl) s.content else ""
                if (streamUrl.startsWith("http", ignoreCase = true)) {
                    val kind = mapVideoStreamKind(s.deliveryMethod, s.format, streamUrl)
                    val height = resolveNewPipeVideoHeight(s.height, s.resolution)
                    candidates.add(
                        MediaCandidate(
                            url = streamUrl,
                            kind = kind,
                            height = height,
                            bitrateKbps = (s.bitrate / 1000).coerceAtLeast(0),
                            mimeType = s.format?.mimeType.orEmpty(),
                            contentLength = s.itagItem?.contentLength?.coerceAtLeast(0L) ?: 0L,
                            headers = baseHeaders,
                            source = "newpipe",
                            isVideoOnly = true,
                            hasAudio = false
                        )
                    )
                }
            }

            info.audioStreams.orEmpty().forEach { s ->
                val streamUrl = if (s.isUrl) s.content else ""
                if (streamUrl.startsWith("http", ignoreCase = true)) {
                    val kbps = s.averageBitrate.takeIf { it > 0 } ?: (s.bitrate / 1000).coerceAtLeast(0)
                    candidates.add(
                        MediaCandidate(
                            url = streamUrl,
                            kind = StreamKind.AUDIO_ONLY,
                            height = 0,
                            bitrateKbps = kbps,
                            mimeType = s.format?.mimeType.orEmpty(),
                            contentLength = s.itagItem?.contentLength?.coerceAtLeast(0L) ?: 0L,
                            headers = baseHeaders,
                            source = "newpipe",
                            isVideoOnly = false,
                            hasAudio = true
                        )
                    )
                }
            }

            info.hlsUrl?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { hls ->
                candidates.add(
                    MediaCandidate(
                        url = hls,
                        kind = StreamKind.HLS_MANIFEST,
                        headers = baseHeaders,
                        source = "newpipe",
                        isVideoOnly = false,
                        hasAudio = true
                    )
                )
            }

            info.dashMpdUrl?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { dash ->
                candidates.add(
                    MediaCandidate(
                        url = dash,
                        kind = StreamKind.DASH_MANIFEST,
                        headers = baseHeaders,
                        source = "newpipe",
                        isVideoOnly = false,
                        hasAudio = true
                    )
                )
            }

            val bestThumb = info.thumbnails.orEmpty()
                .filter { it.url.startsWith("http", ignoreCase = true) }
                .maxByOrNull { it.width }?.url
                .orEmpty()

            val siteName = runCatching { info.service?.serviceInfo?.name?.lowercase(Locale.US) }.getOrNull()
                ?: DomainUtil.normalizeRootDomain(url).orEmpty()

            ExtractedFallbackInfo(
                url = url,
                title = info.name.orEmpty(),
                author = info.uploaderName.orEmpty(),
                durationSeconds = info.duration.coerceAtLeast(0L),
                thumbnail = bestThumb,
                website = siteName,
                candidates = FallbackMediaRules.deduplicateCandidates(candidates),
                sourceEngine = "newpipe"
            )
        }

    suspend fun extractViaDirectHttp(url: String): ExtractedFallbackInfo =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                return@withContext ExtractedFallbackInfo(url = url)
            }

            val baseHeaders = buildPageRequestHeaders(url)
            val directSelf = FallbackMediaRules.classifyMediaCandidate(
                url = url,
                headers = baseHeaders,
                source = "direct_url"
            )

            val website = DomainUtil.normalizeRootDomain(url).orEmpty()
            val reqBuilder = Request.Builder().url(url).header("User-Agent", preferredUaForUrl(url))
            baseHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

            val response = try {
                httpClient.newCall(reqBuilder.build()).execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Direct HTTP probe failed for $url: ${e.message}")
                return@withContext ExtractedFallbackInfo(
                    url = url,
                    website = website,
                    candidates = listOfNotNull(directSelf),
                    sourceEngine = if (directSelf != null) "direct_http" else ""
                )
            }

            response.use { resp ->
                val finalUrl = resp.request.url.toString()
                val rawCt = resp.header("Content-Type").orEmpty()
                val ct = rawCt.substringBefore(';').trim().lowercase(Locale.US)
                val contentLen = resp.header("Content-Length")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

                if (ct.startsWith("image/") && !ct.contains("svg") && !ct.contains("icon")) {
                    return@withContext ExtractedFallbackInfo(
                        url = finalUrl,
                        title = BrowserMediaUtil.inferCleanImageTitle(finalUrl),
                        thumbnail = finalUrl,
                        website = website,
                        imageUrls = listOf(finalUrl),
                        imageMimeType = ct,
                        sourceEngine = "direct_image_http"
                    )
                }

                if (ct.startsWith("video/") ||
                    ct.startsWith("audio/") ||
                    ct.contains("mpegurl") ||
                    ct.contains("dash+xml") ||
                    directSelf != null
                ) {
                    val classified = FallbackMediaRules.classifyMediaCandidate(
                        url = finalUrl,
                        mimeType = ct,
                        headers = buildPageRequestHeaders(finalUrl),
                        source = "direct_http",
                        contentLength = contentLen
                    ) ?: directSelf
                    return@withContext ExtractedFallbackInfo(
                        url = finalUrl,
                        website = website,
                        candidates = listOfNotNull(classified),
                        sourceEngine = "direct_http"
                    )
                }

                if (ct.contains("text/html") || ct.contains("application/xhtml+xml") || ct.isEmpty()) {
                    val html = readBoundedBodyString(resp.body, 1_500_000)
                    val scraped = FallbackMediaRules.extractMediaFromHtml(
                        html = html,
                        pageUrl = finalUrl,
                        defaultHeaders = buildPageRequestHeaders(finalUrl)
                    )
                    return@withContext ExtractedFallbackInfo(
                        url = finalUrl,
                        title = scraped.title,
                        author = scraped.author,
                        thumbnail = scraped.thumbnail,
                        website = website,
                        candidates = scraped.candidates,
                        imageUrls = scraped.imageUrls,
                        sourceEngine = if (scraped.candidates.isNotEmpty() || scraped.imageUrls.isNotEmpty()) "html_scrape" else ""
                    )
                }

                ExtractedFallbackInfo(url = finalUrl, website = website)
            }
        }

    suspend fun extractResultItem(context: Context, url: String): ResultItem? {
        if (BrowserMediaUtil.isDirectStaticImageFile(url)) {
            return BrowserMediaUtil.buildImageOnlyResultItem(url = url, imageUrl = url)
        }

        val info = discoverFallbackInfo(
            context = context,
            url = url,
            sniffTimeoutMs = 10_000L,
            sniffSettleMs = 2_000L
        )
        val formats = candidatesToFormats(info.candidates)
        if (formats.isNotEmpty()) {
            val durationStr = if (info.durationSeconds > 0L) {
                info.durationSeconds.toInt().toStringDuration(Locale.US)
            } else {
                ""
            }
            val title = info.title.ifBlank {
                DomainUtil.normalizeRootDomain(url) ?: url
            }
            val website = info.website.ifBlank {
                DomainUtil.normalizeRootDomain(url).orEmpty()
            }

            return ResultItem(
                id = 0,
                url = url,
                title = title,
                author = info.author,
                duration = durationStr,
                thumb = info.thumbnail,
                website = website,
                playlistTitle = "",
                formats = formats,
                urls = "",
                chapters = ArrayList(),
                playlistURL = ""
            )
        }

        val bestImg = info.imageUrls.firstOrNull { !BrowserMediaUtil.isGenericSiteImageUrl(it) }
            ?: return null
        return BrowserMediaUtil.buildImageOnlyResultItem(
            url = url,
            imageUrl = bestImg,
            title = info.title,
            author = info.author,
            website = info.website,
            mimeType = info.imageMimeType
        )
    }

    suspend fun extractFormats(context: Context, url: String): List<Format> {
        val info = discoverFallbackInfo(
            context = context,
            url = url,
            sniffTimeoutMs = 10_000L,
            sniffSettleMs = 2_000L
        )
        return candidatesToFormats(info.candidates)
    }

    suspend fun download(
        context: Context,
        item: DownloadItem,
        tempFileDir: File,
        processId: String,
        isCancelled: () -> Boolean,
        onProgress: (Int, String) -> Unit
    ): Result<FallbackDownloadResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (isCancelled()) throw RuntimeManager.CanceledException()
            onProgress(2, "Analyzing stream...")

            val mergedCandidates = mutableListOf<MediaCandidate>()
            var resolvedTitle = item.title
            var resolvedAuthor = item.author
            var resolvedThumb = item.thumb
            var engineLabel = ""

            // 1. Seed if item already carries a concrete media URL from previous extraction
            item.format.url?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { seededUrl ->
                val seeded = FallbackMediaRules.classifyMediaCandidate(
                    url = seededUrl,
                    headers = buildPageRequestHeaders(item.url),
                    source = "seeded_format",
                    contentLength = item.format.filesize
                )
                if (seeded != null) {
                    val withHeight = if (seeded.height == 0 && (item.format.height ?: 0) > 0) {
                        seeded.copy(height = item.format.height ?: 0)
                    } else {
                        seeded
                    }
                    mergedCandidates.add(withHeight)
                    engineLabel = "seeded_format"
                }
            }

            if (isCancelled()) throw RuntimeManager.CanceledException()

            // 2. Try NewPipeExtractor first when service supports the URL
            if (mergedCandidates.isEmpty()) {
                val npInfo = extractFromNewPipe(context, item.url)
                if (npInfo != null && npInfo.candidates.isNotEmpty()) {
                    mergedCandidates.addAll(npInfo.candidates)
                    if (resolvedTitle.isBlank()) resolvedTitle = npInfo.title
                    if (resolvedAuthor.isBlank()) resolvedAuthor = npInfo.author
                    if (resolvedThumb.isBlank()) resolvedThumb = npInfo.thumbnail
                    engineLabel = npInfo.sourceEngine
                }
            }

            if (isCancelled()) throw RuntimeManager.CanceledException()

            // 3. Direct HTTP / HTML scrape with live cookies
            if (mergedCandidates.isEmpty()) {
                val directInfo = extractViaDirectHttp(item.url)
                if (directInfo.title.isNotBlank() && resolvedTitle.isBlank()) resolvedTitle = directInfo.title
                if (directInfo.author.isNotBlank() && resolvedAuthor.isBlank()) resolvedAuthor = directInfo.author
                if (directInfo.thumbnail.isNotBlank() && resolvedThumb.isBlank()) resolvedThumb = directInfo.thumbnail
                if (directInfo.candidates.isNotEmpty()) {
                    mergedCandidates.addAll(directInfo.candidates)
                    engineLabel = directInfo.sourceEngine
                }
            }

            if (isCancelled()) throw RuntimeManager.CanceledException()

            // 4. Headless WebView Sniffer for JS-rendered SPAs and login-walled streams
            val needsSniff = mergedCandidates.isEmpty() || shouldAlsoSniff(mergedCandidates, item.type)
            if (needsSniff) {
                onProgress(6, "Loading stream...")
                val sniffed = HeadlessWebViewSniffer.sniff(
                    context = context,
                    pageUrl = item.url,
                    timeoutMs = 15_000L,
                    settleAfterFirstMediaMs = 2_500L
                )
                if (sniffed.title.isNotBlank() && resolvedTitle.isBlank()) resolvedTitle = sniffed.title
                if (sniffed.thumbnail.isNotBlank() && resolvedThumb.isBlank()) resolvedThumb = sniffed.thumbnail
                if (sniffed.candidates.isNotEmpty()) {
                    mergedCandidates.addAll(sniffed.candidates)
                    engineLabel = if (engineLabel.isBlank()) "webview_sniffer" else "$engineLabel+webview"
                }
            }

            if (isCancelled()) throw RuntimeManager.CanceledException()

            val deduped = FallbackMediaRules.deduplicateCandidates(mergedCandidates)
            if (deduped.isEmpty()) {
                throw IllegalStateException("No playable stream found for URL")
            }

            val plans = buildExecutionPlans(deduped, item)
            if (plans.isEmpty()) {
                throw IllegalStateException("No compatible stream found for ${item.type}")
            }

            var lastError: Throwable? = null
            for (attempt in plans.take(3)) {
                if (isCancelled()) throw RuntimeManager.CanceledException()

                tempFileDir.deleteRecursively()
                tempFileDir.mkdirs()

                val safeTitle = FallbackMediaRules.sanitizeFilename(resolvedTitle, item.id)
                val attemptResult = runCatching {
                    executePlan(
                        item = item,
                        plan = attempt,
                        safeTitle = safeTitle,
                        tempFileDir = tempFileDir,
                        processId = processId,
                        isCancelled = isCancelled,
                        onProgress = onProgress
                    )
                }

                if (attemptResult.isSuccess) {
                    val outFile = attemptResult.getOrThrow()
                    cleanTempArtifacts(tempFileDir, keepFile = outFile)
                    if (!outFile.exists() || outFile.length() <= 1024L) {
                        throw IllegalStateException("Downloaded media file is empty or missing")
                    }
                    onProgress(100, "Download complete")
                    return@runCatching FallbackDownloadResult(
                        outputFile = outFile,
                        engineUsed = engineLabel.ifBlank { attempt.primary.source.ifBlank { "fallback" } },
                        resolvedTitle = resolvedTitle.takeIf { it.isNotBlank() },
                        resolvedAuthor = resolvedAuthor.takeIf { it.isNotBlank() },
                        resolvedThumb = resolvedThumb.takeIf { it.isNotBlank() }
                    )
                }

                val err = attemptResult.exceptionOrNull()!!
                if (err is RuntimeManager.CanceledException || err is CancellationException || isCancelled()) {
                    throw RuntimeManager.CanceledException()
                }
                Log.w(TAG, "Fallback attempt failed (${attempt.primary.source}): ${err.message}")
                lastError = err
            }

            throw lastError ?: IllegalStateException("All fallback attempts failed")
        }
    }

    private suspend fun discoverFallbackInfo(
        context: Context,
        url: String,
        sniffTimeoutMs: Long,
        sniffSettleMs: Long
    ): ExtractedFallbackInfo {
        val npInfo = extractFromNewPipe(context, url)
        if (npInfo != null && npInfo.candidates.isNotEmpty()) {
            return npInfo
        }

        val directInfo = extractViaDirectHttp(url)
        if (directInfo.sourceEngine == "direct_image_http") {
            return directInfo
        }
        var title = directInfo.title
        var author = directInfo.author
        var thumb = directInfo.thumbnail
        val website = directInfo.website.ifBlank { DomainUtil.normalizeRootDomain(url).orEmpty() }
        val collected = directInfo.candidates.toMutableList()
        val collectedImages = LinkedHashSet<String>(directInfo.imageUrls)
        var engine = directInfo.sourceEngine

        if (collected.isEmpty()) {
            val sniffed = HeadlessWebViewSniffer.sniff(
                context = context,
                pageUrl = url,
                timeoutMs = sniffTimeoutMs,
                settleAfterFirstMediaMs = sniffSettleMs
            )
            if (sniffed.title.isNotBlank() && title.isBlank()) title = sniffed.title
            if (sniffed.thumbnail.isNotBlank() && thumb.isBlank()) thumb = sniffed.thumbnail
            if (sniffed.imageUrls.isNotEmpty()) {
                collectedImages.addAll(sniffed.imageUrls)
            }
            if (sniffed.candidates.isNotEmpty()) {
                collected.addAll(sniffed.candidates)
                engine = "webview_sniffer"
            } else if (collectedImages.isNotEmpty() && engine.isBlank()) {
                engine = "webview_image_sniffer"
            }
        }

        return ExtractedFallbackInfo(
            url = url,
            title = title,
            author = author,
            durationSeconds = 0L,
            thumbnail = thumb,
            website = website,
            candidates = FallbackMediaRules.deduplicateCandidates(collected),
            imageUrls = collectedImages.toList(),
            imageMimeType = directInfo.imageMimeType,
            sourceEngine = engine
        )
    }

    private fun shouldAlsoSniff(candidates: List<MediaCandidate>, type: DownloadType): Boolean {
        val deduped = FallbackMediaRules.deduplicateCandidates(candidates)
        if (type == DownloadType.audio) {
            return deduped.none { it.kind == StreamKind.AUDIO_ONLY || (!it.isVideoOnly && it.hasAudio) }
        }
        val bestAudio = FallbackMediaRules.pickBestAudioCandidate(deduped)
        val hasPlayableVideo = deduped.any {
            it.kind != StreamKind.AUDIO_ONLY && (!it.isVideoOnly || bestAudio != null)
        }
        if (!hasPlayableVideo) return true

        val onlyLowPriority = deduped.all { c ->
            val l = c.url.lowercase(Locale.US)
            l.contains("preview") || l.contains("poster") || l.contains("thumb") || l.contains("muted")
        }
        return onlyLowPriority
    }

    private fun buildExecutionPlans(
        candidates: List<MediaCandidate>,
        item: DownloadItem
    ): List<ExecutionPlan> {
        val plans = mutableListOf<ExecutionPlan>()
        val targetMaxHeight = FallbackMediaRules.parseRequestedMaxHeight(item)

        if (item.type == DownloadType.audio) {
            val rankedAudio = candidates
                .filter { it.kind == StreamKind.AUDIO_ONLY }
                .sortedByDescending { it.bitrateKbps }
            rankedAudio.forEach { a ->
                plans.add(ExecutionPlan(primary = a, pairedAudio = null, isAudioMode = true))
            }
            // If direct audio fails or is absent, extract audio from ranked video/HLS candidates
            FallbackMediaRules.rankVideoCandidates(candidates, targetMaxHeight).forEach { v ->
                plans.add(ExecutionPlan(primary = v, pairedAudio = null, isAudioMode = true))
            }
            return plans
        }

        val bestAudio = FallbackMediaRules.pickBestAudioCandidate(candidates)
        val rankedVideos = FallbackMediaRules.rankVideoCandidates(candidates, targetMaxHeight)
        rankedVideos.forEach { v ->
            val audio = if (v.isVideoOnly) bestAudio else null
            plans.add(ExecutionPlan(primary = v, pairedAudio = audio, isAudioMode = false))
        }
        return plans
    }

    private fun executePlan(
        item: DownloadItem,
        plan: ExecutionPlan,
        safeTitle: String,
        tempFileDir: File,
        processId: String,
        isCancelled: () -> Boolean,
        onProgress: (Int, String) -> Unit
    ): File {
        val candidate = plan.primary
        if (plan.isAudioMode) {
            val targetExt = FallbackMediaRules.resolveTargetAudioContainer(item)
            val bitrateKbps = FallbackMediaRules.parseRequestedAudioBitrateKbps(item)
            val outFile = File(tempFileDir, "$safeTitle.$targetExt")

            if (candidate.kind == StreamKind.HLS_MANIFEST || candidate.kind == StreamKind.DASH_MANIFEST) {
                onProgress(20, "Downloading audio...")
                val args = FallbackMediaRules.buildHlsOrDashDownloadArgs(
                    manifestUrl = candidate.url,
                    headers = effectiveHeaders(candidate, item.url),
                    outputPath = outFile.absolutePath,
                    isAudioOnly = true,
                    audioContainer = targetExt,
                    audioBitrateKbps = bitrateKbps
                )
                runFfmpegWithProgress(args, processId, tempFileDir, 20, 95, onProgress)
                return outFile
            }

            val rawTemp = File(tempFileDir, "_fb_audio_raw.tmp")
            val downloadedMime = downloadUrlToFile(
                url = candidate.url,
                headers = effectiveHeaders(candidate, item.url),
                outFile = rawTemp,
                isCancelled = isCancelled,
                startPct = 10,
                endPct = 65,
                onProgress = onProgress
            )

            val candidateExt = candidate.url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.US)
            val alreadyTargetFormat = candidate.kind == StreamKind.AUDIO_ONLY &&
                candidateExt == targetExt &&
                (downloadedMime.startsWith("audio/") || downloadedMime.isEmpty())
            if (alreadyTargetFormat && targetExt == "mp3") {
                if (!rawTemp.renameTo(outFile)) {
                    rawTemp.copyTo(outFile, overwrite = true)
                    rawTemp.delete()
                }
                return outFile
            }

            onProgress(70, "Converting audio...")
            val args = FallbackMediaRules.buildAudioExtractArgs(
                inputPath = rawTemp.absolutePath,
                outputPath = outFile.absolutePath,
                audioContainer = targetExt,
                audioBitrateKbps = bitrateKbps
            )
            runFfmpegWithProgress(args, processId, tempFileDir, 70, 98, onProgress)
            rawTemp.delete()
            return outFile
        }

        // Video mode
        val targetExt = FallbackMediaRules.resolveTargetVideoContainer(item)
        val outFile = File(tempFileDir, "$safeTitle.$targetExt")

        if (candidate.kind == StreamKind.HLS_MANIFEST || candidate.kind == StreamKind.DASH_MANIFEST) {
            onProgress(15, "Downloading stream...")
            val copyArgs = FallbackMediaRules.buildHlsOrDashDownloadArgs(
                manifestUrl = candidate.url,
                headers = effectiveHeaders(candidate, item.url),
                outputPath = outFile.absolutePath,
                isAudioOnly = false,
                audioContainer = "m4a",
                audioBitrateKbps = 192,
                transcodeVideoToH264 = false
            )
            val copyAttempt = runCatching {
                runFfmpegWithProgress(copyArgs, processId, tempFileDir, 15, 95, onProgress)
            }
            if (copyAttempt.isSuccess && outFile.exists() && outFile.length() > 1024L) {
                return outFile
            }
            val copyErr = copyAttempt.exceptionOrNull()
            if (copyErr is RuntimeManager.CanceledException || isCancelled()) {
                throw RuntimeManager.CanceledException()
            }
            if (targetExt == "mp4") {
                outFile.delete()
                onProgress(20, "Processing video...")
                val transcodeArgs = FallbackMediaRules.buildHlsOrDashDownloadArgs(
                    manifestUrl = candidate.url,
                    headers = effectiveHeaders(candidate, item.url),
                    outputPath = outFile.absolutePath,
                    isAudioOnly = false,
                    audioContainer = "m4a",
                    audioBitrateKbps = 192,
                    transcodeVideoToH264 = true
                )
                runFfmpegWithProgress(transcodeArgs, processId, tempFileDir, 20, 96, onProgress)
                return outFile
            }
            throw copyErr ?: IllegalStateException("Manifest download failed")
        }

        val pairedAudio = plan.pairedAudio
        if (candidate.isVideoOnly && pairedAudio != null) {
            val videoTmp = File(tempFileDir, "_fb_video.tmp")
            val audioTmp = File(tempFileDir, "_fb_audio.tmp")

            downloadUrlToFile(
                url = candidate.url,
                headers = effectiveHeaders(candidate, item.url),
                outFile = videoTmp,
                isCancelled = isCancelled,
                startPct = 10,
                endPct = 55,
                onProgress = onProgress
            )
            downloadUrlToFile(
                url = pairedAudio.url,
                headers = effectiveHeaders(pairedAudio, item.url),
                outFile = audioTmp,
                isCancelled = isCancelled,
                startPct = 55,
                endPct = 75,
                onProgress = onProgress
            )

            onProgress(78, "Merging video & audio...")
            val audioIsAac = pairedAudio.mimeType.contains("mp4", true) ||
                pairedAudio.mimeType.contains("aac", true) ||
                pairedAudio.url.lowercase(Locale.US).contains(".m4a")
            val copyMuxArgs = FallbackMediaRules.buildMuxVideoAudioArgs(
                videoPath = videoTmp.absolutePath,
                audioPath = audioTmp.absolutePath,
                outputPath = outFile.absolutePath,
                audioCodecCopy = audioIsAac && targetExt == "mp4"
            )
            val muxAttempt = runCatching {
                runFfmpegWithProgress(copyMuxArgs, processId, tempFileDir, 78, 97, onProgress)
            }
            if (muxAttempt.isFailure) {
                val err = muxAttempt.exceptionOrNull()
                if (err is RuntimeManager.CanceledException || isCancelled()) {
                    throw RuntimeManager.CanceledException()
                }
                outFile.delete()
                val reencodeAudioArgs = FallbackMediaRules.buildMuxVideoAudioArgs(
                    videoPath = videoTmp.absolutePath,
                    audioPath = audioTmp.absolutePath,
                    outputPath = outFile.absolutePath,
                    audioCodecCopy = false
                )
                runFfmpegWithProgress(reencodeAudioArgs, processId, tempFileDir, 78, 97, onProgress)
            }

            videoTmp.delete()
            audioTmp.delete()
            return outFile
        }

        // Progressive muxed video
        val sameContainer = (targetExt == "mp4" && candidate.kind == StreamKind.PROGRESSIVE_MP4) ||
            (targetExt == "webm" && candidate.kind == StreamKind.PROGRESSIVE_WEBM)
        if (sameContainer) {
            downloadUrlToFile(
                url = candidate.url,
                headers = effectiveHeaders(candidate, item.url),
                outFile = outFile,
                isCancelled = isCancelled,
                startPct = 10,
                endPct = 95,
                onProgress = onProgress
            )
            return outFile
        }

        val rawTmp = File(tempFileDir, "_fb_raw.tmp")
        downloadUrlToFile(
            url = candidate.url,
            headers = effectiveHeaders(candidate, item.url),
            outFile = rawTmp,
            isCancelled = isCancelled,
            startPct = 10,
            endPct = 75,
            onProgress = onProgress
        )
        onProgress(80, "Converting video...")
        val remuxArgs = mutableListOf("-y", "-i", rawTmp.absolutePath, "-c", "copy")
        if (targetExt == "mp4") {
            remuxArgs.addAll(listOf("-movflags", "+faststart"))
        }
        remuxArgs.add(outFile.absolutePath)
        val remuxAttempt = runCatching {
            runFfmpegWithProgress(remuxArgs, processId, tempFileDir, 80, 97, onProgress)
        }
        if (remuxAttempt.isFailure) {
            val err = remuxAttempt.exceptionOrNull()
            if (err is RuntimeManager.CanceledException || isCancelled()) {
                throw RuntimeManager.CanceledException()
            }
            outFile.delete()
            val transcodeArgs = mutableListOf(
                "-y", "-i", rawTmp.absolutePath,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-b:a", "192k"
            )
            if (targetExt == "mp4") transcodeArgs.addAll(listOf("-movflags", "+faststart"))
            transcodeArgs.add(outFile.absolutePath)
            runFfmpegWithProgress(transcodeArgs, processId, tempFileDir, 80, 97, onProgress)
        }
        rawTmp.delete()
        return outFile
    }

    private fun runFfmpegWithProgress(
        args: List<String>,
        processId: String,
        workDir: File,
        startPct: Int,
        endPct: Int,
        onProgress: (Int, String) -> Unit
    ) {
        var tick = startPct
        RuntimeManager.getInstance().executeFFmpeg(
            args = args,
            processId = processId,
            executeDirectory = workDir
        ) { _, _, _ ->
            if (tick < endPct - 1) {
                tick++
                onProgress(tick, "Processing media...")
            }
        }
    }

    private fun downloadUrlToFile(
        url: String,
        headers: Map<String, String>,
        outFile: File,
        isCancelled: () -> Boolean,
        startPct: Int,
        endPct: Int,
        onProgress: (Int, String) -> Unit
    ): String {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (v.isNotBlank()) reqBuilder.header(k, v)
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            reqBuilder.header("User-Agent", MOBILE_UA)
        }

        val call = httpClient.newCall(reqBuilder.build())
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} while downloading media")
            }
            val ct = resp.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.US)
            if (ct.startsWith("text/html") || ct.startsWith("text/plain") || ct.startsWith("application/json")) {
                throw IllegalStateException("Unexpected non-media content-type: $ct")
            }

            val totalBytes = resp.body.contentLength()
            var downloaded = 0L
            var lastReportedPct = startPct
            onProgress(startPct, "Downloading media...")

            outFile.parentFile?.mkdirs()
            val buffer = ByteArray(16 * 1024)
            resp.body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    while (true) {
                        if (isCancelled()) {
                            call.cancel()
                            throw RuntimeManager.CanceledException()
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0L) {
                            val fraction = (downloaded.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
                            val pct = startPct + ((endPct - startPct) * fraction).toInt()
                            if (pct > lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct, "Downloading media...")
                            }
                        }
                    }
                }
            }

            if (outFile.length() <= 1024L) {
                throw IllegalStateException("Downloaded file too small (${outFile.length()} bytes)")
            }
            return ct
        }
    }

    fun candidatesToFormats(candidates: List<MediaCandidate>): ArrayList<Format> {
        val result = ArrayList<Format>()
        val usedIds = HashSet<String>()

        candidates.forEachIndexed { idx, c ->
            val container = when (c.kind) {
                StreamKind.PROGRESSIVE_MP4, StreamKind.HLS_MANIFEST, StreamKind.DASH_MANIFEST -> "mp4"
                StreamKind.PROGRESSIVE_WEBM -> "webm"
                StreamKind.AUDIO_ONLY -> inferAudioContainer(c)
                StreamKind.PROGRESSIVE_OTHER -> "mp4"
            }
            val vcodec = if (c.kind == StreamKind.AUDIO_ONLY) "none" else if (container == "webm") "vp9" else "h264"
            val acodec = when {
                c.kind == StreamKind.AUDIO_ONLY -> inferAudioCodec(c)
                c.isVideoOnly || !c.hasAudio -> "none"
                container == "webm" -> "opus"
                else -> "aac"
            }
            val note = when (c.kind) {
                StreamKind.AUDIO_ONLY -> if (c.bitrateKbps > 0) "${c.bitrateKbps}kbps Audio" else "Audio"
                StreamKind.HLS_MANIFEST -> if (c.height > 0) "${c.height}p (HLS)" else "HLS"
                StreamKind.DASH_MANIFEST -> if (c.height > 0) "${c.height}p (DASH)" else "DASH"
                else -> if (c.height > 0) "${c.height}p" else "Best"
            }
            var id = when (c.kind) {
                StreamKind.AUDIO_ONLY -> "fb_audio_${if (c.bitrateKbps > 0) c.bitrateKbps else idx}"
                StreamKind.HLS_MANIFEST -> "fb_hls_${if (c.height > 0) c.height else idx}"
                StreamKind.DASH_MANIFEST -> "fb_dash_${if (c.height > 0) c.height else idx}"
                else -> "fb_${container}_${if (c.height > 0) c.height else idx}"
            }
            if (!usedIds.add(id)) {
                id = "${id}_$idx"
                usedIds.add(id)
            }

            result.add(
                Format(
                    format_id = id,
                    container = container,
                    vcodec = vcodec,
                    acodec = acodec,
                    filesize = c.contentLength,
                    format_note = note,
                    url = c.url,
                    tbr = if (c.bitrateKbps > 0) "${c.bitrateKbps}" else "",
                    _height = if (c.height > 0) c.height.toString() else null
                )
            )
        }
        return result
    }

    private fun inferAudioContainer(c: MediaCandidate): String {
        val lowerUrl = c.url.lowercase(Locale.US)
        return when {
            lowerUrl.contains(".mp3") || c.mimeType.contains("mpeg") -> "mp3"
            lowerUrl.contains(".opus") || c.mimeType.contains("opus") -> "opus"
            lowerUrl.contains(".ogg") -> "ogg"
            else -> "m4a"
        }
    }

    private fun inferAudioCodec(c: MediaCandidate): String {
        val lowerUrl = c.url.lowercase(Locale.US)
        return when {
            lowerUrl.contains(".mp3") || c.mimeType.contains("mpeg") -> "mp3"
            lowerUrl.contains(".opus") || c.mimeType.contains("opus") -> "opus"
            else -> "mp4a.40.2"
        }
    }

    private fun mapVideoStreamKind(
        deliveryMethod: DeliveryMethod?,
        mediaFormat: MediaFormat?,
        url: String
    ): StreamKind {
        val lower = url.lowercase(Locale.US)
        if (deliveryMethod == DeliveryMethod.HLS || lower.contains(".m3u8")) {
            return StreamKind.HLS_MANIFEST
        }
        if (deliveryMethod == DeliveryMethod.DASH && lower.contains(".mpd")) {
            return StreamKind.DASH_MANIFEST
        }
        return when (mediaFormat) {
            MediaFormat.MPEG_4 -> StreamKind.PROGRESSIVE_MP4
            MediaFormat.WEBM -> StreamKind.PROGRESSIVE_WEBM
            else -> StreamKind.PROGRESSIVE_OTHER
        }
    }

    private fun resolveNewPipeVideoHeight(height: Int, resolution: String?): Int {
        if (height > 0) return height
        val res = resolution.orEmpty()
        return Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun preferredUaForUrl(url: String): String {
        return runCatching {
            SiteSessionManager.getPreferredUserAgentForUrl(App.instance, url)
        }.getOrNull() ?: MOBILE_UA
    }

    private fun effectiveHeaders(candidate: MediaCandidate, pageUrl: String): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(candidate.headers)
        if (merged.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            merged["User-Agent"] = preferredUaForUrl(pageUrl)
        }
        if (pageUrl.startsWith("http", ignoreCase = true) &&
            merged.keys.none { it.equals("Referer", ignoreCase = true) }
        ) {
            merged["Referer"] = pageUrl
        }
        val liveCookie = runCatching {
            SiteSessionManager.getCookieHeaderForUrl(candidate.url)
                ?: SiteSessionManager.getCookieHeaderForUrl(pageUrl)
        }.getOrNull()
        if (!liveCookie.isNullOrBlank()) {
            merged["Cookie"] = liveCookie
        }
        return merged
    }

    private fun buildPageRequestHeaders(url: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = preferredUaForUrl(url)
        if (url.startsWith("http", ignoreCase = true)) {
            headers["Referer"] = url
        }
        runCatching { SiteSessionManager.getCookieHeaderForUrl(url) }.getOrNull()?.let {
            if (it.isNotBlank()) headers["Cookie"] = it
        }
        return headers
    }

    private fun readBoundedBodyString(body: ResponseBody, maxBytes: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        body.byteStream().use { input ->
            while (total < maxBytes) {
                val read = input.read(buf, 0, minOf(buf.size, maxBytes - total))
                if (read == -1) break
                out.write(buf, 0, read)
                total += read
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun cleanTempArtifacts(dir: File, keepFile: File) {
        dir.listFiles()?.forEach { f ->
            if (f.absolutePath != keepFile.absolutePath && f.name.startsWith("_fb_")) {
                f.delete()
            }
        }
    }
}

package com.deniscerri.ytdl.util.extractors.fallback

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.util.BrowserMediaUtil
import com.deniscerri.ytdl.util.DomainUtil
import com.deniscerri.ytdl.util.SiteSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

object HeadlessWebViewSniffer {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val FALLBACK_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val snifferMutex = Mutex()

    private val DOM_INSPECT_JS = """
        (function() {
            try {
                var ogTitle = document.querySelector('meta[property="og:title"]');
                var twTitle = document.querySelector('meta[name="twitter:title"]');
                var title = (ogTitle && ogTitle.content) || (twTitle && twTitle.content) || document.title || '';

                var ogImg = document.querySelector('meta[property="og:image:secure_url"], meta[property="og:image"]');
                var twImg = document.querySelector('meta[name="twitter:image:src"], meta[name="twitter:image"]');
                var thumb = (ogImg && ogImg.content) || (twImg && twImg.content) || '';

                var media = [];
                var seen = {};
                function addUrl(u, h) {
                    if (!u || typeof u !== 'string') return;
                    if (u.indexOf('http://') !== 0 && u.indexOf('https://') !== 0) return;
                    if (seen[u]) return;
                    seen[u] = true;
                    media.push({ url: u, height: h || 0 });
                }

                var hasVideoTag = document.querySelectorAll('video').length > 0;

                document.querySelectorAll('meta[property="og:video"], meta[property="og:video:secure_url"], meta[property="og:video:url"], meta[name="twitter:player:stream"]').forEach(function(m) {
                    addUrl(m.content, 0);
                });

                document.querySelectorAll('video, audio, source').forEach(function(el) {
                    var h = el.videoHeight || 0;
                    addUrl(el.currentSrc, h);
                    addUrl(el.src, h);
                    try {
                        if (el.tagName && el.tagName.toLowerCase() === 'video') {
                            el.muted = true;
                            var p = el.play();
                            if (p && p.catch) p.catch(function() {});
                        }
                    } catch (e) {}
                });

                var images = [];
                var seenImg = {};
                function addImage(u, w, h) {
                    if (!u || typeof u !== 'string') return;
                    var trimmed = u.trim();
                    if (trimmed.indexOf('http://') !== 0 && trimmed.indexOf('https://') !== 0) return;
                    if (seenImg[trimmed]) return;
                    seenImg[trimmed] = true;
                    images.push({ url: trimmed, w: w || 0, h: h || 0 });
                }

                function bestFromSrcset(ss) {
                    if (!ss || typeof ss !== 'string') return '';
                    var parts = ss.split(',');
                    var best = '';
                    var bestScore = -1;
                    for (var i = 0; i < parts.length; i++) {
                        var item = parts[i].trim();
                        if (!item) continue;
                        var tokens = item.split(/\s+/);
                        var cand = tokens[0] || '';
                        var desc = (tokens[1] || '').toLowerCase();
                        var score = i;
                        if (desc.endsWith('w')) {
                            score = parseInt(desc.slice(0, -1), 10) || score;
                        } else if (desc.endsWith('x')) {
                            score = Math.round((parseFloat(desc.slice(0, -1)) || 1) * 1000);
                        }
                        if (cand && score >= bestScore) {
                            bestScore = score;
                            best = cand;
                        }
                    }
                    return best;
                }

                document.querySelectorAll('meta[property="og:image:secure_url"], meta[property="og:image"], meta[name="twitter:image:src"], meta[name="twitter:image"]').forEach(function(m) {
                    addImage(m.content, 0, 0);
                });

                document.querySelectorAll('img').forEach(function(img) {
                    var alt = (img.getAttribute('alt') || '').toLowerCase();
                    if (alt.indexOf('foto del perfil') !== -1 || alt.indexOf('foto de perfil') !== -1 ||
                        alt.indexOf('imagen de perfil') !== -1 || alt.indexOf('profile picture') !== -1 ||
                        alt.indexOf('profile photo') !== -1 || alt.indexOf('avatar') !== -1) {
                        return;
                    }
                    var rect = img.getBoundingClientRect ? img.getBoundingClientRect() : null;
                    var w = img.naturalWidth || (rect ? Math.round(rect.width) : 0) || parseInt(img.getAttribute('width') || '0', 10) || 0;
                    var h = img.naturalHeight || (rect ? Math.round(rect.height) : 0) || parseInt(img.getAttribute('height') || '0', 10) || 0;
                    if ((w > 0 && w < 150) || (h > 0 && h < 150)) return;
                    var chosen = bestFromSrcset(img.getAttribute('srcset')) || img.currentSrc || img.getAttribute('src') || '';
                    addImage(chosen, w, h);
                });

                if (window.performance && performance.getEntriesByType) {
                    var entries = performance.getEntriesByType('resource') || [];
                    for (var i = 0; i < entries.length; i++) {
                        var name = entries[i].name || '';
                        var lower = name.toLowerCase();
                        if (lower.indexOf('.m3u8') !== -1 || lower.indexOf('.mpd') !== -1 ||
                            lower.indexOf('.mp4') !== -1 || lower.indexOf('.m4a') !== -1) {
                            addUrl(name, 0);
                        }
                    }
                }

                return JSON.stringify({ title: title, thumb: thumb, hasVideoTag: hasVideoTag, media: media, images: images });
            } catch (e) {
                return JSON.stringify({ title: '', thumb: '', hasVideoTag: false, media: [], images: [] });
            }
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(
        context: Context,
        pageUrl: String,
        timeoutMs: Long = 15_000L,
        settleAfterFirstMediaMs: Long = 2_500L
    ): FallbackMediaRules.PageScrapeResult {
        if (!pageUrl.startsWith("http://", true) && !pageUrl.startsWith("https://", true)) {
            return FallbackMediaRules.PageScrapeResult()
        }

        return snifferMutex.withLock {
            val useDesktopMode = withContext(Dispatchers.IO) {
                val rootDomain = DomainUtil.normalizeRootDomain(pageUrl) ?: return@withContext false
                runCatching {
                    DBManager.getInstance(context.applicationContext).siteSessionDao
                        .getByDomain(rootDomain)?.desktopMode == true
                }.getOrDefault(false)
            }

            val candidatesMap = ConcurrentHashMap<String, FallbackMediaRules.MediaCandidate>()
            val imagesMap = ConcurrentHashMap<String, Int>()
            val hasVideoTagRef = AtomicBoolean(false)
            val titleRef = AtomicReference("")
            val thumbRef = AtomicReference("")
            val currentUrlRef = AtomicReference(pageUrl)

            withContext(Dispatchers.Main) {
                val appContext = context.applicationContext
                val selectedUserAgent = if (useDesktopMode) {
                    DESKTOP_UA
                } else {
                    runCatching {
                        WebSettings.getDefaultUserAgent(appContext)
                            .replace("; wv", "")
                            .replace(Regex("Version/\\d+\\.\\d+\\s+"), "")
                    }.getOrDefault(FALLBACK_MOBILE_UA)
                }

                val mainHandler = Handler(Looper.getMainLooper())
                var webView: WebView? = null
                var settleRunnable: Runnable? = null
                var imageFinishRunnable: Runnable? = null
                val delayedInspectRunnables = mutableListOf<Runnable>()

                fun cleanupWebView() {
                    settleRunnable?.let { mainHandler.removeCallbacks(it) }
                    imageFinishRunnable?.let { mainHandler.removeCallbacks(it) }
                    delayedInspectRunnables.forEach { mainHandler.removeCallbacks(it) }
                    delayedInspectRunnables.clear()
                    val target = webView
                    webView = null
                    if (target != null) {
                        runCatching {
                            target.stopLoading()
                            target.webViewClient = WebViewClient()
                            target.loadUrl("about:blank")
                            target.clearHistory()
                            target.removeAllViews()
                            target.destroy()
                        }
                    }
                }

                try {
                    withTimeoutOrNull(timeoutMs) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            val resumed = AtomicBoolean(false)

                            fun finishOnce() {
                                if (resumed.compareAndSet(false, true) && cont.isActive) {
                                    cont.resume(Unit)
                                }
                            }

                            fun scheduleSettleIfNeeded() {
                                if (settleRunnable != null || resumed.get()) return
                                val runnable = Runnable { finishOnce() }
                                settleRunnable = runnable
                                mainHandler.postDelayed(runnable, settleAfterFirstMediaMs)
                            }

                            fun buildMergedHeaders(targetUrl: String, requestHeaders: Map<String, String>): Map<String, String> {
                                val merged = LinkedHashMap<String, String>()
                                merged.putAll(requestHeaders)
                                if (merged.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                                    merged["User-Agent"] = selectedUserAgent
                                }
                                val ref = currentUrlRef.get()
                                if (ref.startsWith("http", ignoreCase = true) &&
                                    merged.keys.none { it.equals("Referer", ignoreCase = true) }
                                ) {
                                    merged["Referer"] = ref
                                }
                                val liveCookie = SiteSessionManager.getCookieHeaderForUrl(targetUrl)
                                    ?: SiteSessionManager.getCookieHeaderForUrl(ref)
                                if (!liveCookie.isNullOrBlank()) {
                                    merged["Cookie"] = liveCookie
                                }
                                return merged
                            }

                            fun recordCandidate(candidate: FallbackMediaRules.MediaCandidate) {
                                val existing = candidatesMap[candidate.url]
                                if (existing == null) {
                                    candidatesMap[candidate.url] = candidate
                                    scheduleSettleIfNeeded()
                                } else if (candidate.height > existing.height) {
                                    candidatesMap[candidate.url] = existing.copy(
                                        height = candidate.height,
                                        headers = existing.headers + candidate.headers
                                    )
                                }
                            }

                            fun inspectDomMedia(view: WebView?) {
                                if (view == null || resumed.get()) return
                                runCatching {
                                    view.evaluateJavascript(DOM_INSPECT_JS) { rawResult ->
                                        if (rawResult.isNullOrBlank() || rawResult == "null") return@evaluateJavascript
                                        runCatching {
                                            val decoded = JSONTokener(rawResult).nextValue() as? String
                                                ?: rawResult
                                            val json = JSONObject(decoded)
                                            val title = json.optString("title").trim()
                                            val thumb = json.optString("thumb").trim()
                                            if (title.isNotEmpty() && titleRef.get().isEmpty()) {
                                                titleRef.set(title)
                                            }
                                            if (thumb.startsWith("http") &&
                                                thumbRef.get().isEmpty() &&
                                                !BrowserMediaUtil.isGenericSiteImageUrl(thumb)
                                            ) {
                                                thumbRef.set(thumb)
                                            }
                                            if (json.optBoolean("hasVideoTag", false)) {
                                                hasVideoTagRef.set(true)
                                            }
                                            val imgArr = json.optJSONArray("images")
                                            if (imgArr != null) {
                                                for (i in 0 until imgArr.length()) {
                                                    val obj = imgArr.optJSONObject(i) ?: continue
                                                    val imgUrl = obj.optString("url").trim()
                                                    if (imgUrl.isEmpty() ||
                                                        !BrowserMediaUtil.isLikelyContentImageUrl(imgUrl) ||
                                                        BrowserMediaUtil.isGenericSiteImageUrl(imgUrl)
                                                    ) {
                                                        continue
                                                    }
                                                    val w = obj.optInt("w", 0)
                                                    val h = obj.optInt("h", 0)
                                                    val score = BrowserMediaUtil.scoreImageCandidate(imgUrl, w, h, "headless_dom")
                                                    imagesMap.compute(imgUrl) { _, prev ->
                                                        if (prev == null) score else maxOf(prev, score)
                                                    }
                                                }
                                                val topImg = imagesMap.entries.maxByOrNull { it.value }?.key
                                                if (!topImg.isNullOrBlank() && thumbRef.get().isEmpty()) {
                                                    thumbRef.set(topImg)
                                                }
                                            }
                                            val mediaArr = json.optJSONArray("media")
                                            if (mediaArr != null) {
                                                val pageHeaders = buildMergedHeaders(currentUrlRef.get(), emptyMap())
                                                for (i in 0 until mediaArr.length()) {
                                                    val obj = mediaArr.optJSONObject(i) ?: continue
                                                    val mediaUrl = obj.optString("url").trim()
                                                    val domHeight = obj.optInt("height", 0)
                                                    val candidate = FallbackMediaRules.classifyMediaCandidate(
                                                        url = mediaUrl,
                                                        headers = pageHeaders,
                                                        source = "webview_dom"
                                                    ) ?: continue
                                                    val finalCandidate = if (candidate.height == 0 && domHeight > 0 &&
                                                        candidate.kind != FallbackMediaRules.StreamKind.AUDIO_ONLY
                                                    ) {
                                                        candidate.copy(height = domHeight)
                                                    } else {
                                                        candidate
                                                    }
                                                    recordCandidate(finalCandidate)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            val created = runCatching { WebView(appContext) }.getOrNull()
                            if (created == null) {
                                finishOnce()
                                return@suspendCancellableCoroutine
                            }
                            webView = created

                            runCatching {
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(created, true)
                            }

                            created.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                blockNetworkImage = true
                                userAgentString = selectedUserAgent
                            }

                            created.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) {
                                        currentUrlRef.set(url)
                                    }
                                    super.onPageStarted(view, url, favicon)
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString().orEmpty()
                                    if (reqUrl.isNotEmpty()) {
                                        val reqHeaders = request?.requestHeaders ?: emptyMap()
                                        val mergedHeaders = buildMergedHeaders(reqUrl, reqHeaders)
                                        val candidate = FallbackMediaRules.classifyMediaCandidate(
                                            url = reqUrl,
                                            headers = mergedHeaders,
                                            source = "webview_net"
                                        )
                                        if (candidate != null) {
                                            candidatesMap.compute(candidate.url) { _, prev ->
                                                if (prev == null) candidate
                                                else prev.copy(
                                                    height = maxOf(prev.height, candidate.height),
                                                    headers = prev.headers + candidate.headers
                                                )
                                            }
                                            mainHandler.post { scheduleSettleIfNeeded() }
                                        }
                                    }
                                    return null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) {
                                        currentUrlRef.set(url)
                                    }
                                    inspectDomMedia(view)
                                    val delayed = Runnable { inspectDomMedia(view) }
                                    delayedInspectRunnables.add(delayed)
                                    mainHandler.postDelayed(delayed, 1_800L)
                                    imageFinishRunnable?.let { mainHandler.removeCallbacks(it) }
                                    val finishCheck = Runnable {
                                        if (candidatesMap.isEmpty() && !hasVideoTagRef.get() && imagesMap.isNotEmpty()) {
                                            finishOnce()
                                        }
                                    }
                                    imageFinishRunnable = finishCheck
                                    mainHandler.postDelayed(finishCheck, 2_500L)
                                    super.onPageFinished(view, url)
                                }
                            }

                            cont.invokeOnCancellation {
                                mainHandler.post { cleanupWebView() }
                            }

                            created.loadUrl(pageUrl)
                        }
                    }
                } finally {
                    cleanupWebView()
                }
            }

            FallbackMediaRules.PageScrapeResult(
                title = titleRef.get(),
                thumbnail = thumbRef.get(),
                candidates = candidatesMap.values.toList(),
                imageUrls = imagesMap.entries.sortedByDescending { it.value }.map { it.key }
            )
        }
    }
}

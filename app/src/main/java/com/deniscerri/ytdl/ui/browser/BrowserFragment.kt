package com.deniscerri.ytdl.ui.browser

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.TextUtils
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.BrowserViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.SiteSessionViewModel
import com.deniscerri.ytdl.util.AccessibilityUtil
import com.deniscerri.ytdl.util.BrowserMediaUtil
import com.deniscerri.ytdl.util.BrowserScriptLoader
import com.deniscerri.ytdl.util.DomainUtil
import com.deniscerri.ytdl.util.DownloadPresetManager
import com.deniscerri.ytdl.util.Extensions.isYoutubeURL
import com.deniscerri.ytdl.util.ImageDownloadHelper
import com.deniscerri.ytdl.util.MediaHistoryMatcher
import com.deniscerri.ytdl.util.QualityCapsuleUtil
import com.deniscerri.ytdl.util.SiteSessionManager
import com.deniscerri.ytdl.util.UiUtil
import com.deniscerri.ytdl.util.extractors.fallback.FallbackMediaRules
import com.deniscerri.ytdl.util.extractors.fallback.LocalFallbackEngine
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.hypot

class BrowserFragment : Fragment() {

    private lateinit var browserViewModel: BrowserViewModel
    private lateinit var siteSessionViewModel: SiteSessionViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel

    private var webView: WebView? = null
    private lateinit var toolbarRow: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var backBtn: MaterialButton
    private lateinit var menuBtn: MaterialButton
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var sessionDot: View
    private lateinit var quickPlatformsScroll: HorizontalScrollView
    private lateinit var quickPlatformsChips: ChipGroup
    private lateinit var emptyState: LinearLayout
    private lateinit var downloadFab: MaterialButton
    private lateinit var pageMediaBtn: MaterialButton
    private lateinit var batchSelectBtn: MaterialButton

    private lateinit var mediaContextBar: MaterialCardView
    private lateinit var mediaContextThumb: ImageView
    private lateinit var mediaContextLabel: TextView
    private lateinit var mediaContextSublabel: TextView
    private lateinit var mediaContextDownloadBtn: MaterialButton
    private lateinit var mediaContextMenuBtn: MaterialButton
    private lateinit var mediaContextCloseBtn: MaterialButton

    private var accessibilityManager: AccessibilityManager? = null
    private val accessibilityStateListener = AccessibilityManager.AccessibilityStateChangeListener {
        view?.post { if (isAdded) syncWebViewAccessibilityMode() }
    }
    private val touchExplorationListener = AccessibilityManager.TouchExplorationStateChangeListener {
        view?.post { if (isAdded) syncWebViewAccessibilityMode() }
    }

    private var lastReceivedFavicon: Bitmap? = null
    private var lastCandidateIcons: List<String> = emptyList()
    private var isPageLoading: Boolean = false
    private var hasSavedSession: Boolean = false
    private var lastSessionStatusSummary: String = ""
    private var lastDownloadLabel: String = ""
    private var shouldFocusWebViewOnNextLoad: Boolean = false
    private var lastAnnouncedPageKey: String = ""

    private var defaultMobileUa: String = ""
    private var desktopChromeUa: String = ""
    private var chromiumVersion: BrowserMediaUtil.ChromiumVersion =
        BrowserMediaUtil.ChromiumVersion("136.0.0.0", "136")

    private val detectedMedia = ConcurrentHashMap<String, FallbackMediaRules.MediaCandidate>()
    private val detectedImages = ConcurrentHashMap<String, DetectedImageEntry>()
    private val scannedItemsByProfile = LinkedHashMap<String, LinkedHashMap<String, ScannedPageMediaItem>>()
    private var highlightRefreshJob: Job? = null

    @Volatile
    private var currentPageUrl: String = ""
    @Volatile
    private var currentPageUa: String = ""

    private var touchSlopPx: Float = 24f
    private var touchSeq: Long = 0L
    private var longPressHandledSeq: Long = -1L
    private var lastTouchDownX: Float = 0f
    private var lastTouchDownY: Float = 0f
    private var lastTouchDownTime: Long = 0L
    private var touchMovedBeyondSlop: Boolean = false

    @Volatile
    private var pendingDownHit: HitMediaResult? = null
    private var lastTouchedMedia: HitMediaResult? = null
    private var activeContextDialog: BottomSheetDialog? = null

    data class DetectedImageEntry(
        val url: String,
        val width: Int = 0,
        val height: Int = 0,
        val title: String = "",
        val postUrl: String = "",
        val source: String = "network",
        val capturedAtMs: Long = System.currentTimeMillis()
    )

    data class HitMediaResult(
        val seqId: Long = 0L,
        val imageUrl: String = "",
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        val imageTitle: String = "",
        val videoUrls: List<String> = emptyList(),
        val hasVideoElement: Boolean = false,
        val postUrl: String = "",
        val pageTitle: String = "",
        val pageThumb: String = "",
        val postImages: List<DetectedImageEntry> = emptyList(),
        val postVideos: List<String> = emptyList()
    ) {
        fun isLikelyVideo(currentUrl: String): Boolean {
            return hasVideoElement ||
                videoUrls.isNotEmpty() ||
                postVideos.isNotEmpty() ||
                BrowserMediaUtil.isLikelyVideoPostUrl(postUrl.ifBlank { currentUrl }) ||
                BrowserMediaUtil.hasVideoPlayerHint(imageTitle)
        }

        fun hasAnyMedia(): Boolean {
            return imageUrl.isNotBlank() ||
                hasVideoElement ||
                videoUrls.isNotEmpty() ||
                postImages.isNotEmpty() ||
                postVideos.isNotEmpty()
        }
    }

    private data class PreparedBrowserDownload(
        val resultItem: ResultItem,
        val detectedType: DownloadType,
        val forceUpdateData: Boolean
    )

    private data class GalleryMediaItem(
        val url: String,
        val isVideo: Boolean,
        val title: String,
        val postUrl: String,
        val subtitle: String,
        val width: Int = 0,
        val height: Int = 0,
        val thumbUrl: String = ""
    )

    private data class ScannedPageMediaItem(
        val key: String,
        val url: String,
        val title: String,
        val thumb: String,
        val duration: String,
        val author: String,
        val isVideo: Boolean,
        val state: MediaHistoryMatcher.MediaDownloadState = MediaHistoryMatcher.MediaDownloadState.NEW
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_browser, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        browserViewModel = ViewModelProvider(requireActivity())[BrowserViewModel::class.java]
        siteSessionViewModel = ViewModelProvider(requireActivity())[SiteSessionViewModel::class.java]
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]

        toolbarRow = view.findViewById(R.id.browser_toolbar_row)
        urlInput = view.findViewById(R.id.browser_url_input)
        backBtn = view.findViewById(R.id.btn_browser_back)
        menuBtn = view.findViewById(R.id.btn_browser_menu)
        progressIndicator = view.findViewById(R.id.browser_progress)
        sessionDot = view.findViewById(R.id.browser_session_dot)
        quickPlatformsScroll = view.findViewById(R.id.browser_quick_platforms_scroll)
        quickPlatformsChips = view.findViewById(R.id.browser_quick_platforms_chips)
        emptyState = view.findViewById(R.id.browser_empty_state)
        downloadFab = view.findViewById(R.id.fab_browser_download)
        pageMediaBtn = view.findViewById(R.id.btn_browser_page_media)
        batchSelectBtn = view.findViewById(R.id.btn_browser_batch_select)
        webView = view.findViewById(R.id.browser_webview)

        mediaContextBar = view.findViewById(R.id.browser_media_context_bar)
        mediaContextThumb = view.findViewById(R.id.browser_context_thumb)
        mediaContextLabel = view.findViewById(R.id.browser_context_label)
        mediaContextSublabel = view.findViewById(R.id.browser_context_sublabel)
        mediaContextDownloadBtn = view.findViewById(R.id.btn_browser_context_download)
        mediaContextMenuBtn = view.findViewById(R.id.btn_browser_context_menu)
        mediaContextCloseBtn = view.findViewById(R.id.btn_browser_context_close)

        touchSlopPx = ViewConfiguration.get(requireContext()).scaledTouchSlop.toFloat()

        accessibilityManager = requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        accessibilityManager?.addAccessibilityStateChangeListener(accessibilityStateListener)
        accessibilityManager?.addTouchExplorationStateChangeListener(touchExplorationListener)

        val rawDefaultUa = runCatching { WebSettings.getDefaultUserAgent(requireContext()) }.getOrDefault("")
        chromiumVersion = BrowserMediaUtil.extractChromiumVersion(rawDefaultUa)
        defaultMobileUa = BrowserMediaUtil.buildMobileChromeUserAgent(rawDefaultUa)
        desktopChromeUa = BrowserMediaUtil.buildDesktopChromeUserAgent(chromiumVersion.fullVersion)

        setupQuickPlatforms()
        setupWebView()
        syncWebViewAccessibilityMode()
        setupControls()
        setupBackNavigation()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    browserViewModel.pendingUrl.collectLatest { pending ->
                        if (!pending.isNullOrBlank()) {
                            val target = browserViewModel.consumePendingUrl()
                            if (!target.isNullOrBlank()) {
                                loadAddress(target)
                            }
                        }
                    }
                }
                launch {
                    downloadViewModel.alreadyExistsUiState.collectLatest { res ->
                        if (res.isNotEmpty()) {
                            delay(350)
                            val nav = runCatching { findNavController() }.getOrNull()
                            if (isAdded && nav?.currentDestination?.id == R.id.browserFragment) {
                                runCatching {
                                    nav.navigate(
                                        R.id.action_browserFragment_to_downloadsAlreadyExistDialog,
                                        bundleOf("duplicates" to ArrayList(res))
                                    )
                                }
                            }
                            downloadViewModel.alreadyExistsUiState.value = mutableListOf()
                        }
                    }
                }
            }
        }

        val initialTarget = browserViewModel.consumePendingUrl()
        if (!initialTarget.isNullOrBlank()) {
            loadAddress(initialTarget)
        } else if (savedInstanceState != null) {
            val restored = runCatching { webView?.restoreState(savedInstanceState) }.getOrNull()
            val restoredUrl = restored?.currentItem?.url?.takeIf { it.startsWith("http") }
                ?: webView?.url?.takeIf { it.startsWith("http") }
            if (restored != null && !restoredUrl.isNullOrBlank()) {
                currentPageUrl = restoredUrl
                lastAnnouncedPageKey = ""
                setPageLoadedState(true)
                if (quickPlatformsScroll.isVisible) {
                    quickPlatformsScroll.isVisible = false
                }
                setUrlInputQuietly(restoredUrl)
                browserViewModel.setCurrentUrl(restoredUrl)
                updateDownloadFabLabel()
            } else {
                val fallbackUrl = browserViewModel.currentUrl.value
                if (!fallbackUrl.isNullOrBlank()) {
                    loadAddress(fallbackUrl)
                } else {
                    setPageLoadedState(false)
                }
            }
        } else {
            val fallbackUrl = browserViewModel.currentUrl.value
            if (!fallbackUrl.isNullOrBlank()) {
                loadAddress(fallbackUrl)
            } else {
                setPageLoadedState(false)
            }
        }
    }

    private fun setupQuickPlatforms() {
        quickPlatformsChips.removeAllViews()
        val platforms = WebRulesRegistry.current().quickPlatforms
        platforms.forEach { platform ->
            val chip = Chip(requireContext()).apply {
                text = platform.name
                contentDescription = getString(R.string.open_platform_in_browser, platform.name)
                isCheckable = false
                setOnClickListener { loadAddress(platform.url) }
            }
            quickPlatformsChips.addView(chip)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        webView?.apply {
            cm.setAcceptThirdPartyCookies(this, true)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

            settings.apply {
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
            }

            configureWebViewHardening(settings)
            applyUserAgentAndViewport(this, browserViewModel.desktopMode.value)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!isAdded) return
                    val show = newProgress in 1..99
                    if (progressIndicator.isVisible != show) {
                        progressIndicator.isVisible = show
                    }
                    if (show) {
                        progressIndicator.progress = newProgress
                    }
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    if (icon != null) {
                        lastReceivedFavicon = icon
                    }
                }

                override fun getDefaultVideoPoster(): Bitmap {
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    hideFloatingContextBar()
                    if (view != null && callback != null) {
                        (activity as? MainActivity)?.enterWebFullscreen(view, callback)
                    } else {
                        runCatching { callback?.onCustomViewHidden() }
                    }
                }

                override fun onHideCustomView() {
                    (activity as? MainActivity)?.exitWebFullscreen()
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val hostWebView = view ?: return false
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val popupWebView = WebView(hostWebView.context)
                    popupWebView.settings.javaScriptEnabled = true
                    popupWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            popupView: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val targetUrl = request?.url?.toString().orEmpty()
                            if (targetUrl.startsWith("http")) {
                                hostWebView.post { hostWebView.loadUrl(targetUrl) }
                            }
                            runCatching { popupView?.destroy() }
                            return true
                        }

                        override fun onPageStarted(popupView: WebView?, url: String?, favicon: Bitmap?) {
                            if (!url.isNullOrBlank() && url.startsWith("http")) {
                                hostWebView.post { hostWebView.loadUrl(url) }
                                popupView?.stopLoading()
                                runCatching { popupView?.destroy() }
                            }
                        }
                    }
                    transport.webView = popupWebView
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    val safeResources = request.resources.filter {
                        it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    }.toTypedArray()
                    if (safeResources.isNotEmpty()) {
                        request.grant(safeResources)
                    } else {
                        request.deny()
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!isAdded) return
                    isPageLoading = true
                    if (!progressIndicator.isVisible) {
                        progressIndicator.isVisible = true
                    }
                    if (favicon != null) {
                        lastReceivedFavicon = favicon
                    }
                    if (!url.isNullOrBlank() && url.startsWith("http")) {
                        val prevDomain = DomainUtil.normalizeRootDomain(currentPageUrl)
                        val newDomain = DomainUtil.normalizeRootDomain(url)
                        val prevPath = runCatching { URI(currentPageUrl).path?.trimEnd('/') }.getOrNull()
                        val newPath = runCatching { URI(url).path?.trimEnd('/') }.getOrNull()
                        if (prevDomain != newDomain || (prevPath != null && newPath != null && prevPath != newPath)) {
                            detectedMedia.clear()
                            hideFloatingContextBar()
                        }
                        if (prevDomain != null && newDomain != null && prevDomain != newDomain) {
                            detectedImages.clear()
                            hasSavedSession = false
                            lastSessionStatusSummary = ""
                            sessionDot.isVisible = false
                            refreshUrlInputContentDescription()
                        } else if (prevDomain == null && url != currentPageUrl) {
                            detectedImages.clear()
                        }
                        currentPageUrl = url
                        updateDownloadFabLabel()

                        setPageLoadedState(true)
                        if (quickPlatformsScroll.isVisible) {
                            quickPlatformsScroll.isVisible = false
                        }
                        setUrlInputQuietly(url)
                        browserViewModel.setCurrentUrl(url)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (!isAdded || isReload || url.isNullOrBlank() || !url.startsWith("http")) return
                    val prevDomain = DomainUtil.normalizeRootDomain(currentPageUrl)
                    val newDomain = DomainUtil.normalizeRootDomain(url)
                    val prevPath = runCatching { URI(currentPageUrl).path?.trimEnd('/') }.getOrNull()
                    val newPath = runCatching { URI(url).path?.trimEnd('/') }.getOrNull()
                    if (prevDomain != newDomain || (prevPath != null && newPath != null && prevPath != newPath)) {
                        detectedMedia.clear()
                        hideFloatingContextBar()
                    }
                    if (url != currentPageUrl) {
                        currentPageUrl = url
                        setUrlInputQuietly(url)
                        browserViewModel.setCurrentUrl(url)
                        updateDownloadFabLabel()
                        refreshDownloadedHighlightsOnPage(delayMs = 700L)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request == null || !request.method.equals("GET", ignoreCase = true)) {
                        return null
                    }
                    val reqUrl = request.url?.toString().orEmpty()
                    val pageRef = currentPageUrl
                    if (reqUrl.startsWith("http", ignoreCase = true) &&
                        pageRef.startsWith("http", ignoreCase = true)
                    ) {
                        capturePassiveMediaFromRequest(view, reqUrl, pageRef, request.requestHeaders)
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isAdded) return
                    isPageLoading = false
                    if (progressIndicator.isVisible) {
                        progressIndicator.isVisible = false
                    }

                    if (url.isNullOrBlank() || !url.startsWith("http")) {
                        setPageLoadedState(false)
                        return
                    }

                    currentPageUrl = url
                    setPageLoadedState(true)
                    if (quickPlatformsScroll.isVisible) {
                        quickPlatformsScroll.isVisible = false
                    }
                    setUrlInputQuietly(url)
                    browserViewModel.setCurrentUrl(url)

                    val pageLabel = view?.title?.trim()?.takeIf { it.isNotBlank() }
                        ?: DomainUtil.displayNameForDomain(
                            DomainUtil.normalizeRootDomain(url).orEmpty(),
                            null
                        )
                    if (view != null && pageLabel.isNotBlank()) {
                        val announceKey = "$url|$pageLabel"
                        if (announceKey != lastAnnouncedPageKey) {
                            lastAnnouncedPageKey = announceKey
                            AccessibilityUtil.announcePolitely(
                                view,
                                getString(R.string.browser_page_loaded, pageLabel)
                            )
                        }
                    }

                    if (shouldFocusWebViewOnNextLoad) {
                        shouldFocusWebViewOnNextLoad = false
                        if (!urlInput.hasFocus()) {
                            view?.postDelayed({
                                if (isAdded) focusWebViewContent(announce = false)
                            }, 250L)
                        }
                    }

                    runCatching { CookieManager.getInstance().flush() }
                    inspectPageAndSyncSession(view, url, forceSave = false)
                    refreshDownloadedHighlightsOnPage(delayMs = 900L)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val scheme = request?.url?.scheme?.lowercase(Locale.US) ?: return false
                    return scheme != "http" && scheme != "https"
                }
            }
        }
    }

    private fun syncWebViewAccessibilityMode() {
        val wv = webView ?: return
        val screenReaderActive = AccessibilityUtil.isScreenReaderActive(context)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        wv.importantForAccessibility = if (wv.isVisible) {
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        wv.isClickable = false
        wv.isLongClickable = true
        if (screenReaderActive) {
            // Keep WebView host free of raw touch traps so TalkBack & Jieshuo enter Chromium's DOM tree directly
            wv.setOnTouchListener(null)
        } else {
            setupTouchListener()
        }
        setupLongPressListener()
    }

    private fun syncAppBottomBarVisibility(pageLoaded: Boolean) {
        (activity as? MainActivity)?.setBrowserBottomNavHidden(
            pageLoaded && !browserViewModel.showAppNavBar.value
        )
    }

    private fun setPageLoadedState(loaded: Boolean) {
        val wv = webView
        if (wv != null) {
            if (wv.isVisible != loaded) {
                wv.isVisible = loaded
            }
            val expectedImportance = if (loaded) {
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            if (wv.importantForAccessibility != expectedImportance) {
                wv.importantForAccessibility = expectedImportance
            }
        }

        val showEmpty = !loaded
        if (emptyState.isVisible != showEmpty) {
            emptyState.isVisible = showEmpty
        }
        val emptyImportance = if (showEmpty) {
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        if (emptyState.importantForAccessibility != emptyImportance) {
            emptyState.importantForAccessibility = emptyImportance
        }

        if (downloadFab.isVisible != loaded) {
            downloadFab.isVisible = loaded
        }
        if (pageMediaBtn.isVisible != loaded) {
            pageMediaBtn.isVisible = loaded
        }
        if (batchSelectBtn.isVisible != loaded) {
            batchSelectBtn.isVisible = loaded
        }
        val showDot = loaded && hasSavedSession
        if (sessionDot.isVisible != showDot) {
            sessionDot.isVisible = showDot
        }
        syncAppBottomBarVisibility(loaded)
    }

    private fun refreshUrlInputContentDescription() {
        val baseDesc = getString(R.string.browser_url_bar_desc)
        urlInput.contentDescription = if (lastSessionStatusSummary.isNotBlank()) {
            "$lastSessionStatusSummary. $baseDesc"
        } else {
            baseDesc
        }
    }

    private fun setUrlInputQuietly(url: String) {
        if (urlInput.hasFocus()) return
        if (urlInput.text?.toString() == url) return
        val srActive = AccessibilityUtil.isScreenReaderActive(context)
        if (srActive) {
            urlInput.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        urlInput.setText(url)
        if (srActive) {
            urlInput.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    private fun focusWebViewContent(announce: Boolean = false) {
        val wv = webView ?: return
        if (!isAdded || activeContextDialog?.isShowing == true) return
        if (!wv.isVisible) {
            setPageLoadedState(true)
        }
        if (urlInput.hasFocus()) {
            urlInput.clearFocus()
        }
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.requestFocus()
        AccessibilityUtil.sendFocusedEvent(wv)
        if (AccessibilityUtil.isScreenReaderActive(context)) {
            val enteredDom = runCatching {
                wv.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT,
                    Bundle().apply {
                        putString(AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING, "")
                    }
                )
            }.getOrDefault(false)
            if (!enteredDom) {
                runCatching {
                    wv.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                }
            }
            if (announce) {
                val label = wv.title?.trim()?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.browser_focus_page_content)
                AccessibilityUtil.announcePolitely(wv, label)
            }
        }
    }

    private fun openContextMenuFromFocusedOrViewport() {
        val currentUrl = webView?.url ?: browserViewModel.currentUrl.value
        if (currentUrl.isNullOrBlank() || !currentUrl.startsWith("http")) return
        viewLifecycleOwner.lifecycleScope.launch {
            val inspected = inspectMediaAtPoint(-1f, -1f, ++touchSeq)
            if (isAdded) {
                showMediaContextBottomSheet(inspected)
            }
        }
    }

    private fun configureWebViewHardening(settings: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            runCatching {
                WebSettingsCompat.setWebAuthenticationSupport(
                    settings,
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
                )
            }
        }
    }

    private fun applyUserAgentAndViewport(wv: WebView, desktop: Boolean) {
        val targetUa = if (desktop) desktopChromeUa else defaultMobileUa
        currentPageUa = targetUa
        wv.settings.apply {
            userAgentString = targetUa
            // Honor viewport=device-width 1:1 in mobile mode; allow zoomed overview only in desktop mode
            useWideViewPort = true
            loadWithOverviewMode = desktop
        }
        wv.setInitialScale(0)
        applyUserAgentMetadata(wv.settings, desktop)
    }

    private fun applyUserAgentMetadata(settings: WebSettings, desktop: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Not/A)Brand")
                    .setMajorVersion("8")
                    .setFullVersion("8.0.0.0")
                    .build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Chromium")
                    .setMajorVersion(chromiumVersion.majorVersion)
                    .setFullVersion(chromiumVersion.fullVersion)
                    .build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Google Chrome")
                    .setMajorVersion(chromiumVersion.majorVersion)
                    .setFullVersion(chromiumVersion.fullVersion)
                    .build()
            )
            val builder = UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(chromiumVersion.fullVersion)
                .setPlatform(if (desktop) "Windows" else "Android")
                .setPlatformVersion(if (desktop) "15.0.0" else Build.VERSION.RELEASE)
                .setArchitecture(if (desktop) "x86" else "")
                .setBitness(if (desktop) 64 else UserAgentMetadata.BITNESS_DEFAULT)
                .setModel(if (desktop) "" else Build.MODEL)
                .setMobile(!desktop)

            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
                builder.setFormFactors(
                    listOf(
                        if (desktop) UserAgentMetadata.FORM_FACTOR_DESKTOP
                        else UserAgentMetadata.FORM_FACTOR_MOBILE
                    )
                )
            }
            WebSettingsCompat.setUserAgentMetadata(settings, builder.build())
        }
    }

    private fun capturePassiveMediaFromRequest(
        view: WebView?,
        reqUrl: String,
        pageRef: String,
        requestHeaders: Map<String, String>?
    ) {
        val cheapVideo = FallbackMediaRules.classifyMediaCandidate(
            url = reqUrl,
            source = "browser_live"
        )
        val isImage = cheapVideo == null && BrowserMediaUtil.isLikelyContentImageUrl(reqUrl)
        if (cheapVideo == null && !isImage) return

        if (cheapVideo != null) {
            val mergedHeaders = LinkedHashMap<String, String>()
            requestHeaders?.let { mergedHeaders.putAll(it) }
            if (mergedHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                val ua = currentPageUa.ifBlank { defaultMobileUa }
                if (ua.isNotBlank()) mergedHeaders["User-Agent"] = ua
            }
            if (mergedHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
                mergedHeaders["Referer"] = pageRef
            }
            val liveCookie = SiteSessionManager.getCookieHeaderForUrl(reqUrl)
                ?: SiteSessionManager.getCookieHeaderForUrl(pageRef)
            if (!liveCookie.isNullOrBlank()) {
                mergedHeaders["Cookie"] = liveCookie
            }

            val enriched = cheapVideo.copy(headers = mergedHeaders)
            val prevSize = detectedMedia.size
            detectedMedia.compute(enriched.url) { _, prev ->
                if (prev == null) enriched else prev.copy(
                    height = maxOf(prev.height, enriched.height),
                    headers = prev.headers + enriched.headers
                )
            }
            if (detectedMedia.size > MAX_CACHED_VIDEOS) {
                detectedMedia.keys.take(detectedMedia.size - MAX_CACHED_VIDEOS).forEach {
                    detectedMedia.remove(it)
                }
            }
            if (detectedMedia.size != prevSize) {
                view?.post { if (isAdded) updateDownloadFabLabel() }
            }
        } else {
            detectedImages[reqUrl] = DetectedImageEntry(
                url = reqUrl,
                postUrl = pageRef,
                source = "network",
                capturedAtMs = System.currentTimeMillis()
            )
            if (detectedImages.size > MAX_CACHED_IMAGES) {
                detectedImages.entries
                    .sortedBy { it.value.capturedAtMs }
                    .take(detectedImages.size - MAX_CACHED_IMAGES)
                    .forEach { detectedImages.remove(it.key) }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        val wv = webView ?: return

        wv.setOnTouchListener { view, event ->
            if (AccessibilityUtil.isScreenReaderActive(view.context)) {
                return@setOnTouchListener false
            }
            val w = view.width.toFloat()
            val h = view.height.toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val seq = ++touchSeq
                    longPressHandledSeq = -1L
                    lastTouchDownX = event.x
                    lastTouchDownY = event.y
                    lastTouchDownTime = System.currentTimeMillis()
                    touchMovedBeyondSlop = false
                    pendingDownHit = null

                    if (w > 0f && h > 0f) {
                        val normX = (event.x / w).coerceIn(0f, 1f)
                        val normY = (event.y / h).coerceIn(0f, 1f)
                        viewLifecycleOwner.lifecycleScope.launch {
                            val hit = inspectMediaAtPoint(normX, normY, seq)
                            if (isAdded && touchSeq == seq) {
                                pendingDownHit = hit
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!touchMovedBeyondSlop &&
                        hypot(event.x - lastTouchDownX, event.y - lastTouchDownY) > touchSlopPx
                    ) {
                        touchMovedBeyondSlop = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val seq = touchSeq
                    val elapsed = System.currentTimeMillis() - lastTouchDownTime
                    if (!touchMovedBeyondSlop && elapsed < 450L && seq != longPressHandledSeq && w > 0f && h > 0f) {
                        val normX = (event.x / w).coerceIn(0f, 1f)
                        val normY = (event.y / h).coerceIn(0f, 1f)
                        viewLifecycleOwner.lifecycleScope.launch {
                            val immediate = pendingDownHit?.takeIf { it.seqId == seq }
                                ?: inspectMediaAtPoint(normX, normY, seq)
                            if (!isAdded || touchSeq != seq || seq == longPressHandledSeq) return@launch
                            if (immediate.hasAnyMedia()) {
                                showFloatingContextBar(immediate)
                            }
                            delay(450L)
                            if (!isAdded || touchSeq != seq || seq == longPressHandledSeq) return@launch
                            val delayed = inspectMediaAtPoint(normX, normY, seq)
                            if (delayed.hasAnyMedia()) {
                                showFloatingContextBar(delayed)
                            }
                        }
                    }
                }
            }
            // Never consume touch events so scrolling, clicking and native WebView links keep working
            false
        }
    }

    private fun setupLongPressListener() {
        val wv = webView ?: return
        wv.setOnLongClickListener {
            if (AccessibilityUtil.isScreenReaderActive(wv.context)) {
                openContextMenuFromFocusedOrViewport()
                return@setOnLongClickListener true
            }
            val seq = touchSeq
            val hitTest = runCatching { wv.hitTestResult }.getOrNull()
            val nativeExtraImage = hitTest?.takeIf {
                it.type == WebView.HitTestResult.IMAGE_TYPE ||
                    it.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            }?.extra?.takeIf { it.isNotBlank() && BrowserMediaUtil.isLikelyContentImageUrl(it) }

            val liveUrl = wv.url?.takeIf { it.startsWith("http") } ?: currentPageUrl
            val readyHit = pendingDownHit?.takeIf { it.seqId == seq }
            if ((readyHit != null && (readyHit.hasAnyMedia() || readyHit.isLikelyVideo(liveUrl))) || nativeExtraImage != null) {
                longPressHandledSeq = seq
                val baseHit = readyHit ?: HitMediaResult(
                    seqId = seq,
                    postUrl = liveUrl,
                    pageTitle = wv.title.orEmpty()
                )
                val merged = if (baseHit.imageUrl.isBlank() && !nativeExtraImage.isNullOrBlank()) {
                    baseHit.copy(imageUrl = nativeExtraImage, pageThumb = nativeExtraImage)
                } else {
                    baseHit
                }
                showMediaContextBottomSheet(merged)
                return@setOnLongClickListener true
            }

            longPressHandledSeq = seq
            val normX = if (wv.width > 0) (lastTouchDownX / wv.width).coerceIn(0f, 1f) else -1f
            val normY = if (wv.height > 0) (lastTouchDownY / wv.height).coerceIn(0f, 1f) else -1f
            viewLifecycleOwner.lifecycleScope.launch {
                val resolved = inspectMediaAtPoint(normX, normY, seq)
                if (!isAdded) return@launch
                val currentLiveUrl = webView?.url?.takeIf { it.startsWith("http") } ?: liveUrl
                if (resolved.hasAnyMedia() || resolved.isLikelyVideo(currentLiveUrl) || currentLiveUrl.startsWith("http")) {
                    showMediaContextBottomSheet(resolved)
                }
            }
            true
        }
    }

    private fun setupControls() {
        backBtn.setOnClickListener {
            if ((activity as? MainActivity)?.isInWebFullscreen == true) {
                (activity as? MainActivity)?.exitWebFullscreen()
            } else if (webView?.canGoBack() == true) {
                webView?.goBack()
            } else {
                (activity as? MainActivity)?.navigateToMainTab(R.id.homeFragment)
            }
        }

        backBtn.setOnLongClickListener {
            (activity as? MainActivity)?.navigateToMainTab(R.id.homeFragment)
            true
        }

        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (!quickPlatformsScroll.isVisible) {
                    quickPlatformsScroll.isVisible = true
                }
            } else if (!webView?.url.isNullOrBlank()) {
                if (quickPlatformsScroll.isVisible) {
                    quickPlatformsScroll.isVisible = false
                }
            }
        }

        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadAddress(urlInput.text?.toString().orEmpty())
                hideKeyboard()
                urlInput.clearFocus()
                true
            } else {
                false
            }
        }

        menuBtn.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.browser_toolbar_menu, popup.menu)
            val currentUrl = webView?.url.orEmpty()
            val hasHttpUrl = currentUrl.startsWith("http")
            popup.menu.findItem(R.id.menu_browser_reload)?.apply {
                title = getString(
                    if (isPageLoading) R.string.browser_action_stop else R.string.browser_action_reload
                )
                isEnabled = hasHttpUrl || urlInput.text?.toString()?.isNotBlank() == true
            }
            popup.menu.findItem(R.id.menu_browser_forward)?.isEnabled = webView?.canGoForward() == true
            popup.menu.findItem(R.id.menu_browser_toggle_app_bar)?.isChecked = browserViewModel.showAppNavBar.value
            popup.menu.findItem(R.id.menu_browser_desktop)?.isChecked = browserViewModel.desktopMode.value
            popup.menu.findItem(R.id.menu_browser_detect_media)?.isEnabled = hasHttpUrl
            popup.menu.findItem(R.id.menu_browser_batch_select)?.isEnabled = hasHttpUrl
            popup.menu.findItem(R.id.menu_browser_focus_web)?.isEnabled = hasHttpUrl
            popup.menu.findItem(R.id.menu_browser_save_session)?.apply {
                isEnabled = hasHttpUrl
                if (hasHttpUrl && lastSessionStatusSummary.isNotBlank()) {
                    title = lastSessionStatusSummary
                }
            }
            popup.menu.findItem(R.id.menu_browser_refresh_session)?.isEnabled = hasHttpUrl
            popup.menu.findItem(R.id.menu_browser_clear_cookies)?.isEnabled = hasHttpUrl
            popup.menu.findItem(R.id.menu_browser_external)?.isEnabled = hasHttpUrl

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_browser_reload -> {
                        if (isPageLoading) {
                            webView?.stopLoading()
                            isPageLoading = false
                            progressIndicator.isVisible = false
                        } else {
                            val current = webView?.url
                            if (!current.isNullOrBlank()) {
                                detectedMedia.clear()
                                detectedImages.clear()
                                hideFloatingContextBar()
                                webView?.reload()
                            } else {
                                val typed = urlInput.text?.toString().orEmpty()
                                if (typed.isNotBlank()) loadAddress(typed)
                            }
                        }
                        true
                    }
                    R.id.menu_browser_forward -> {
                        if (webView?.canGoForward() == true) {
                            webView?.goForward()
                        }
                        true
                    }
                    R.id.menu_browser_detect_media -> {
                        if (hasHttpUrl) openContextMenuFromFocusedOrViewport()
                        true
                    }
                    R.id.menu_browser_batch_select -> {
                        if (hasHttpUrl) openChannelMultiSelectSheet()
                        true
                    }
                    R.id.menu_browser_focus_web -> {
                        if (hasHttpUrl) focusWebViewContent(announce = true)
                        true
                    }
                    R.id.menu_browser_toggle_app_bar -> {
                        val next = !browserViewModel.showAppNavBar.value
                        browserViewModel.setShowAppNavBar(next)
                        syncAppBottomBarVisibility(webView?.isVisible == true)
                        true
                    }
                    R.id.menu_browser_go_home -> {
                        (activity as? MainActivity)?.navigateToMainTab(R.id.homeFragment)
                        true
                    }
                    R.id.menu_browser_go_downloads -> {
                        (activity as? MainActivity)?.navigateToMainTab(R.id.historyFragment)
                        true
                    }
                    R.id.menu_browser_desktop -> {
                        val next = !browserViewModel.desktopMode.value
                        browserViewModel.setDesktopMode(next)
                        webView?.let {
                            applyUserAgentAndViewport(it, next)
                            it.reload()
                        }
                        true
                    }
                    R.id.menu_browser_save_session -> {
                        if (hasHttpUrl) {
                            inspectPageAndSyncSession(webView, currentUrl, forceSave = true, showSavedToast = true)
                        }
                        true
                    }
                    R.id.menu_browser_refresh_session -> {
                        if (hasHttpUrl) refreshCurrentSession(currentUrl)
                        true
                    }
                    R.id.menu_browser_clear_cookies -> {
                        if (hasHttpUrl) clearCurrentSiteCookies(currentUrl)
                        true
                    }
                    R.id.menu_browser_import_cookies -> {
                        showImportCookiesDialog()
                        true
                    }
                    R.id.menu_browser_external -> {
                        if (hasHttpUrl) UiUtil.openLinkIntent(requireContext(), currentUrl)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        mediaContextDownloadBtn.setOnClickListener {
            val hit = lastTouchedMedia ?: return@setOnClickListener
            openUnifiedDownloadSheet(hit, preferredType = null)
        }

        mediaContextMenuBtn.setOnClickListener {
            val hit = lastTouchedMedia ?: return@setOnClickListener
            showMediaContextBottomSheet(hit)
        }

        mediaContextCloseBtn.setOnClickListener {
            hideFloatingContextBar()
        }

        pageMediaBtn.setOnClickListener {
            openContextMenuFromFocusedOrViewport()
        }

        batchSelectBtn.setOnClickListener {
            openChannelMultiSelectSheet()
        }

        downloadFab.setOnClickListener {
            val currentUrl = webView?.url ?: browserViewModel.currentUrl.value ?: return@setOnClickListener
            if (!currentUrl.startsWith("http")) return@setOnClickListener

            viewLifecycleOwner.lifecycleScope.launch {
                val inspected = inspectMediaAtPoint(-1f, -1f, touchSeq)
                openUnifiedDownloadSheet(inspected, preferredType = null)
            }
        }

        downloadFab.setOnLongClickListener {
            val currentUrl = webView?.url ?: browserViewModel.currentUrl.value ?: return@setOnLongClickListener false
            if (!currentUrl.startsWith("http")) return@setOnLongClickListener false

            viewLifecycleOwner.lifecycleScope.launch {
                val pageHit = inspectMediaAtPoint(-1f, -1f, touchSeq)
                if (pageHit.isLikelyVideo(currentUrl)) {
                    queueQuickVideoDownload(pageHit)
                } else {
                    val merged = mergeHitWithFallback(pageHit, currentUrl)
                    if (merged.imageUrl.isNotBlank()) {
                        queueQuickImageDownload(merged)
                    } else {
                        queueQuickVideoDownload(pageHit)
                    }
                }
            }
            true
        }
    }

    private fun showImportCookiesDialog() {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            hint = getString(R.string.import_cookies_hint)
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        if (clipText.contains('\t') && BrowserMediaUtil.parseNetscapeCookieText(clipText).isNotEmpty()) {
            input.setText(clipText)
        }

        val container = ScrollView(ctx).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.import_cookies)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val raw = input.text?.toString().orEmpty()
                if (raw.isBlank()) return@setPositiveButton
                val appCtx = ctx.applicationContext
                val fallbackDomain = DomainUtil.normalizeRootDomain(currentPageUrl)
                val ua = currentPageUa.ifBlank { defaultMobileUa }
                viewLifecycleOwner.lifecycleScope.launch {
                    val sessions = withContext(Dispatchers.IO) {
                        SiteSessionManager.importNetscapeCookies(
                            context = appCtx,
                            rawCookieText = raw,
                            fallbackDomain = fallbackDomain,
                            userAgent = ua
                        )
                    }
                    if (!isAdded) return@launch
                    if (sessions.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.import_cookies_empty, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.import_cookies_success, sessions.size),
                            Toast.LENGTH_SHORT
                        ).show()
                        val activeRoot = DomainUtil.normalizeRootDomain(currentPageUrl)
                        if (activeRoot != null && sessions.any { it.domain == activeRoot }) {
                            webView?.reload()
                        }
                    }
                }
            }
            .show()
    }

    private fun setupBackNavigation() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val mainActivity = activity as? MainActivity
                    when {
                        mainActivity?.isInWebFullscreen == true -> mainActivity.exitWebFullscreen()
                        ::mediaContextBar.isInitialized && mediaContextBar.isVisible -> hideFloatingContextBar()
                        webView?.canGoBack() == true -> webView?.goBack()
                        else -> {
                            mainActivity?.setBrowserBottomNavHidden(false)
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        )
    }

    private fun inspectPageAndSyncSession(
        view: WebView?,
        url: String,
        forceSave: Boolean,
        showSavedToast: Boolean = false
    ) {
        val pageTitle = view?.title
        val userAgent = view?.settings?.userAgentString ?: defaultMobileUa
        val desktop = browserViewModel.desktopMode.value
        val faviconSnapshot = lastReceivedFavicon
        val ctx = context

        if (view != null && ctx != null) {
            val js = BrowserScriptLoader.getIconInspectorScript(ctx)
            view.evaluateJavascript(js) { rawJson ->
                if (!isAdded) return@evaluateJavascript
                val parsedIcons = parseIconList(rawJson)
                lastCandidateIcons = parsedIcons
                performSessionSync(
                    url = url,
                    pageTitle = pageTitle,
                    candidateIcons = parsedIcons,
                    faviconBitmap = faviconSnapshot,
                    desktopMode = desktop,
                    userAgent = userAgent,
                    forceSave = forceSave,
                    showSavedToast = showSavedToast
                )
            }
        } else {
            performSessionSync(
                url = url,
                pageTitle = pageTitle,
                candidateIcons = lastCandidateIcons,
                faviconBitmap = faviconSnapshot,
                desktopMode = desktop,
                userAgent = userAgent,
                forceSave = forceSave,
                showSavedToast = showSavedToast
            )
        }
    }

    private fun parseIconList(rawJson: String?): List<String> {
        if (rawJson.isNullOrBlank() || rawJson == "null") return emptyList()
        return runCatching {
            val unescaped = unescapeJsJsonString(rawJson) ?: return@runCatching emptyList()
            val obj = JSONObject(unescaped)
            val arr: JSONArray = obj.optJSONArray("icons") ?: return@runCatching emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val u = arr.optString(i)
                if (u.startsWith("http")) list.add(u)
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun performSessionSync(
        url: String,
        pageTitle: String?,
        candidateIcons: List<String>,
        faviconBitmap: Bitmap?,
        desktopMode: Boolean,
        userAgent: String,
        forceSave: Boolean,
        showSavedToast: Boolean
    ) {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val synced = SiteSessionManager.syncSessionFromBrowser(
                    context = ctx,
                    pageUrl = url,
                    pageTitle = pageTitle,
                    candidateIconUrls = candidateIcons,
                    receivedIconBitmap = faviconBitmap,
                    desktopMode = desktopMode,
                    userAgent = userAgent,
                    forceSave = forceSave
                )
                val rootDomain = DomainUtil.normalizeRootDomain(url)
                val liveCookies = rootDomain?.let {
                    SiteSessionManager.extractLiveCookiesForDomain(ctx, it)
                }.orEmpty()
                Pair(synced, liveCookies.size)
            }
            if (!isAdded) return@launch

            val syncedSession = result.first
            val liveCookieCount = result.second

            if (syncedSession != null) {
                val labelText = "${syncedSession.displayName} · ${getString(R.string.session_saved_cookies, syncedSession.cookieCount)} (${getString(R.string.session_active)})"
                hasSavedSession = true
                lastSessionStatusSummary = labelText
                val showDot = webView?.isVisible == true
                if (sessionDot.isVisible != showDot) sessionDot.isVisible = showDot
                refreshUrlInputContentDescription()
                if (showSavedToast) {
                    Toast.makeText(requireContext(), R.string.session_saved, Toast.LENGTH_SHORT).show()
                }
            } else if (liveCookieCount > 0) {
                val domain = DomainUtil.normalizeRootDomain(url).orEmpty()
                hasSavedSession = false
                lastSessionStatusSummary = "$domain · ${getString(R.string.session_saved_cookies, liveCookieCount)}"
                if (sessionDot.isVisible) sessionDot.isVisible = false
                refreshUrlInputContentDescription()
            } else {
                hasSavedSession = false
                lastSessionStatusSummary = ""
                if (sessionDot.isVisible) sessionDot.isVisible = false
                refreshUrlInputContentDescription()
            }
        }
    }

    private fun refreshCurrentSession(url: String) {
        val domain = DomainUtil.normalizeRootDomain(url) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val refreshed = siteSessionViewModel.refreshSession(domain)
            if (refreshed != null) {
                if (isAdded) {
                    hasSavedSession = true
                    lastSessionStatusSummary = "${refreshed.displayName} · ${getString(R.string.session_saved_cookies, refreshed.cookieCount)} (${getString(R.string.session_active)})"
                    sessionDot.isVisible = webView?.isVisible == true
                    refreshUrlInputContentDescription()
                    Toast.makeText(requireContext(), R.string.session_refreshed, Toast.LENGTH_SHORT).show()
                }
            } else {
                inspectPageAndSyncSession(webView, url, forceSave = true, showSavedToast = true)
            }
        }
    }

    private fun clearCurrentSiteCookies(url: String) {
        val domain = DomainUtil.normalizeRootDomain(url) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            siteSessionViewModel.removeSession(domain, clearBrowserCookies = true)
            if (!isAdded) return@launch
            hasSavedSession = false
            lastSessionStatusSummary = ""
            sessionDot.isVisible = false
            refreshUrlInputContentDescription()
            Toast.makeText(requireContext(), R.string.session_removed, Toast.LENGTH_SHORT).show()
            webView?.reload()
        }
    }

    private fun loadAddress(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        val resolved = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            Patterns.WEB_URL.matcher(trimmed).matches() && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(trimmed, "UTF-8")}"
        }

        detectedMedia.clear()
        detectedImages.clear()
        hideFloatingContextBar()
        currentPageUrl = resolved
        lastReceivedFavicon = null
        lastCandidateIcons = emptyList()
        shouldFocusWebViewOnNextLoad = true
        lastAnnouncedPageKey = ""

        setPageLoadedState(true)
        if (quickPlatformsScroll.isVisible) {
            quickPlatformsScroll.isVisible = false
        }
        setUrlInputQuietly(resolved)
        browserViewModel.setCurrentUrl(resolved)
        updateDownloadFabLabel()
        webView?.loadUrl(resolved)
    }

    private fun updateDownloadFabLabel() {
        if (!isAdded) return
        val url = currentPageUrl
        val nextText = when {
            BrowserMediaUtil.isLikelyVideoPostUrl(url) -> getString(R.string.browser_download_video)
            !BrowserMediaUtil.isFeedOrListingUrl(url) && detectedMedia.size == 1 -> getString(R.string.browser_download_video)
            BrowserMediaUtil.isDirectStaticImageFile(url) || BrowserMediaUtil.isLikelyPhotoPageUrl(url) ->
                getString(R.string.browser_download_image)
            else -> getString(R.string.download)
        }
        if (nextText != lastDownloadLabel) {
            lastDownloadLabel = nextText
            downloadFab.contentDescription = nextText
        }
    }

    private fun showFloatingContextBar(hit: HitMediaResult) {
        if (!isAdded) return
        val pageUrl = webView?.url ?: currentPageUrl
        val isVideo = hit.isLikelyVideo(pageUrl)
        val isImage = hit.imageUrl.isNotBlank()
        if (!isVideo && !isImage) return

        lastTouchedMedia = hit
        if (!mediaContextBar.isVisible) mediaContextBar.isVisible = true

        val label = when {
            isVideo -> getString(R.string.media_detected_video)
            isImage -> getString(R.string.media_detected_image)
            else -> getString(R.string.media_detected_generic)
        }
        mediaContextLabel.text = label

        val domainName = DomainUtil.displayNameForDomain(
            DomainUtil.normalizeRootDomain(hit.postUrl.ifBlank { pageUrl }).orEmpty(),
            null
        )
        val sub = listOf(hit.imageTitle, hit.pageTitle)
            .firstOrNull { it.isNotBlank() && !BrowserMediaUtil.isGenericPlaceholderTitle(it, domainName) }
            ?: domainName
        if (sub.isNotBlank()) {
            mediaContextSublabel.isVisible = true
            mediaContextSublabel.text = sub
        } else {
            mediaContextSublabel.isVisible = false
        }

        val previewTarget = hit.imageUrl.ifBlank { hit.pageThumb }
        mediaContextThumb.setImageResource(if (isVideo && !isImage) R.drawable.ic_video else R.drawable.ic_image)
        if (previewTarget.startsWith("http") || previewTarget.startsWith("data:image/", ignoreCase = true)) {
            val expectedSeq = hit.seqId
            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = context?.applicationContext ?: return@launch
                val bmp = ImageDownloadHelper.fetchPreviewBitmap(ctx, previewTarget, pageUrl)
                if (isAdded && bmp != null && lastTouchedMedia?.seqId == expectedSeq) {
                    mediaContextThumb.setImageBitmap(bmp)
                }
            }
        }
        AccessibilityUtil.announcePolitely(mediaContextBar, label)
    }

    private fun hideFloatingContextBar() {
        lastTouchedMedia = null
        if (::mediaContextBar.isInitialized && mediaContextBar.isVisible) {
            mediaContextBar.isVisible = false
        }
    }

    private fun collectPostGalleryItems(hit: HitMediaResult, currentUrl: String): List<GalleryMediaItem> {
        val items = linkedMapOf<String, GalleryMediaItem>()
        val scopedPostUrl = hit.postUrl.ifBlank { currentUrl }

        if (hit.imageUrl.isNotBlank()) {
            val dims = if (hit.imageWidth > 0 && hit.imageHeight > 0) {
                " · ${hit.imageWidth}×${hit.imageHeight}"
            } else ""
            items[hit.imageUrl] = GalleryMediaItem(
                url = hit.imageUrl,
                isVideo = false,
                title = hit.imageTitle.ifBlank { hit.pageTitle },
                postUrl = scopedPostUrl,
                subtitle = "${getString(R.string.image)}$dims",
                width = hit.imageWidth,
                height = hit.imageHeight,
                thumbUrl = hit.imageUrl
            )
        }

        hit.postImages.forEach { img ->
            if (!items.containsKey(img.url)) {
                val dims = if (img.width > 0 && img.height > 0) " · ${img.width}×${img.height}" else ""
                items[img.url] = GalleryMediaItem(
                    url = img.url,
                    isVideo = false,
                    title = img.title.ifBlank { hit.pageTitle },
                    postUrl = img.postUrl.ifBlank { scopedPostUrl },
                    subtitle = "${getString(R.string.image)}$dims",
                    width = img.width,
                    height = img.height,
                    thumbUrl = img.url
                )
            }
        }

        val dedupImageKeys = BrowserMediaUtil.deduplicateCarouselImageUrls(
            items.values.filter { !it.isVideo }.map { it.url }
        ).toSet()

        (hit.videoUrls + hit.postVideos).distinct().forEach { vUrl ->
            items.putIfAbsent(
                vUrl,
                GalleryMediaItem(
                    url = vUrl,
                    isVideo = true,
                    title = hit.imageTitle.ifBlank { hit.pageTitle },
                    postUrl = scopedPostUrl,
                    subtitle = getString(R.string.video),
                    thumbUrl = hit.imageUrl.ifBlank { hit.pageThumb }
                )
            )
        }

        return items.values.filter { it.isVideo || it.url in dedupImageKeys }
    }

    private fun showMediaContextBottomSheet(rawHit: HitMediaResult) {
        if (!isAdded) return
        val ctx = requireContext()
        val currentUrl = webView?.url ?: currentPageUrl
        val hit = mergeHitWithFallback(rawHit, currentUrl)
        val isVideo = hit.isLikelyVideo(currentUrl)

        activeContextDialog?.dismiss()
        val dialog = BottomSheetDialog(ctx)
        activeContextDialog = dialog

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }

        val thumbCard = MaterialCardView(ctx).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val thumbView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageResource(
                if (hit.imageUrl.isBlank() && isVideo) R.drawable.ic_video
                else R.drawable.ic_image
            )
        }
        thumbCard.addView(thumbView, LinearLayout.LayoutParams(dp(52), dp(52)))
        headerRow.addView(thumbCard, LinearLayout.LayoutParams(dp(52), dp(52)))

        val headerTextCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val targetPermalink = resolveCanonicalTargetUrl(hit, currentUrl)
        val domainName = DomainUtil.displayNameForDomain(
            DomainUtil.normalizeRootDomain(targetPermalink).orEmpty(),
            null
        )
        val effectiveTitle = listOf(hit.imageTitle, hit.pageTitle)
            .firstOrNull { it.isNotBlank() && !BrowserMediaUtil.isGenericPlaceholderTitle(it, domainName) }
            ?: domainName
            .ifBlank { getString(R.string.download) }

        val titleView = TextView(ctx).apply {
            text = effectiveTitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        AccessibilityUtil.setHeading(titleView, true)

        val dimsText = if (hit.imageWidth > 0 && hit.imageHeight > 0) {
            " (${hit.imageWidth}×${hit.imageHeight})"
        } else ""
        val subtitleLabel = when {
            isVideo -> getString(R.string.media_detected_video)
            hit.imageUrl.isNotBlank() -> getString(R.string.media_detected_image) + dimsText
            else -> getString(R.string.media_detected_generic)
        }
        val subView = TextView(ctx).apply {
            text = subtitleLabel
            alpha = 0.72f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerTextCol.addView(titleView)
        headerTextCol.addView(subView)
        headerRow.addView(headerTextCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(headerRow)

        val previewUrl = hit.imageUrl.ifBlank { hit.pageThumb }
        if (previewUrl.startsWith("http") || previewUrl.startsWith("data:image/", ignoreCase = true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                val bmp = ImageDownloadHelper.fetchPreviewBitmap(ctx.applicationContext, previewUrl, currentUrl)
                if (bmp != null && dialog.isShowing) {
                    thumbView.setImageBitmap(bmp)
                }
            }
        }

        fun addActionButton(label: String, iconRes: Int, tonal: Boolean = false, onClick: () -> Unit) {
            val styleAttr = if (tonal) {
                com.google.android.material.R.attr.materialButtonTonalStyle
            } else {
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            }
            val btn = MaterialButton(ctx, null, styleAttr).apply {
                text = label
                minHeight = dp(48)
                setIconResource(iconRes)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                insetTop = dp(4)
                insetBottom = dp(4)
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            root.addView(btn, lp)
        }

        if (isVideo) {
            addActionButton(getString(R.string.context_download_video_now), R.drawable.ic_video, tonal = true) {
                queueQuickVideoDownload(hit)
            }
        } else if (hit.imageUrl.isNotBlank()) {
            addActionButton(getString(R.string.context_download_image_now), R.drawable.ic_down, tonal = true) {
                queueQuickImageDownload(hit)
            }
        }

        addActionButton(getString(R.string.context_choose_any_format), R.drawable.ic_format, tonal = !isVideo && hit.imageUrl.isBlank()) {
            openUnifiedDownloadSheet(hit, preferredType = null)
        }

        if (isVideo && hit.imageUrl.isNotBlank()) {
            addActionButton(getString(R.string.context_download_cover_image), R.drawable.ic_image) {
                queueQuickImageDownload(hit)
            }
        } else if (!isVideo) {
            addActionButton(getString(R.string.context_download_as_video_audio), R.drawable.ic_video) {
                openUnifiedDownloadSheet(hit, preferredType = DownloadType.video)
            }
        }

        val postGalleryItems = collectPostGalleryItems(hit, currentUrl)
        if (postGalleryItems.size > 1) {
            addActionButton(getString(R.string.context_view_post_media, postGalleryItems.size), R.drawable.ic_image) {
                showAllDetectedMediaSheet(postGalleryItems, currentUrl)
            }
        }

        val cachedPageItemsCount = scannedItemsByProfile[buildProfileScopeKey(currentUrl)]?.size ?: 0
        if (cachedPageItemsCount >= 2) {
            addActionButton(getString(R.string.context_batch_select_page, cachedPageItemsCount), R.drawable.ic_select_all) {
                openChannelMultiSelectSheet()
            }
        }

        val canonicalPostCopy = BrowserMediaUtil.canonicalizePermalinkUrl(hit.postUrl)
            .takeIf { it.startsWith("http") && BrowserMediaUtil.scorePermalinkCandidate(it) >= 0 }
        val canonicalCurrentCopy = BrowserMediaUtil.canonicalizePermalinkUrl(currentUrl)
            .takeIf { it.startsWith("http") && !BrowserMediaUtil.isFeedOrListingUrl(it) }
        val linkToCopy = canonicalPostCopy
            ?: canonicalCurrentCopy
            ?: hit.videoUrls.firstOrNull { it.startsWith("http") }
            ?: hit.imageUrl.takeIf { it.startsWith("http") }
            ?: currentUrl
        if (linkToCopy.startsWith("http")) {
            addActionButton(getString(R.string.context_copy_link), R.drawable.ic_copy) {
                UiUtil.copyLinkToClipBoard(requireContext(), linkToCopy)
            }
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun showAllDetectedMediaSheet(items: List<GalleryMediaItem>, currentUrl: String) {
        if (!isAdded) return
        val ctx = requireContext()
        activeContextDialog?.dismiss()
        val dialog = BottomSheetDialog(ctx)
        activeContextDialog = dialog

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val primaryColor = MaterialColors.getColor(
            ctx,
            androidx.appcompat.R.attr.colorPrimary,
            0xFF6750A4.toInt()
        )
        val rippleTyped = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleTyped, true)

        val visibleItems = items.take(30)
        val selectedIndices = linkedSetOf<Int>()
        var selectedImageExt = "jpg"
        val checkBoxes = mutableListOf<CheckBox>()
        val cards = mutableListOf<MaterialCardView>()

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val header = TextView(ctx).apply {
            text = "${getString(R.string.browser_media_gallery_title)} (${visibleItems.size})"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        AccessibilityUtil.setHeading(header, true)
        outer.addView(header)

        val actionBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        val selectedCountTv = TextView(ctx).apply {
            text = getString(R.string.gallery_selected_count, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.85f
        }
        actionBar.addView(selectedCountTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btnSelectAll = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.gallery_select_all)
            minHeight = dp(38)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        actionBar.addView(btnSelectAll)
        outer.addView(actionBar)

        if (visibleItems.any { !it.isVideo }) {
            val chipGroup = ChipGroup(ctx).apply {
                isSingleSelection = true
                isSelectionRequired = true
                setPadding(dp(4), 0, dp(4), dp(8))
            }
            val radiusPx = 18f * density
            QualityCapsuleUtil.IMAGE_FORMAT_CAPSULES.forEach { capsule ->
                val chip = Chip(ctx).apply {
                    id = View.generateViewId()
                    text = capsule.label
                    contentDescription = getString(R.string.select_option_desc, capsule.label)
                    isCheckable = true
                    isCheckedIconVisible = false
                    chipCornerRadius = radiusPx
                    isChecked = capsule.extension == selectedImageExt
                    setOnClickListener {
                        selectedImageExt = capsule.extension
                    }
                }
                chipGroup.addView(chip)
            }
            outer.addView(chipGroup)
        }

        val btnDownloadSelected = MaterialButton(ctx).apply {
            text = getString(R.string.gallery_download_selected, 0)
            isEnabled = false
            minimumHeight = dp(48)
        }

        fun syncSelectionUi() {
            val count = selectedIndices.size
            selectedCountTv.text = getString(R.string.gallery_selected_count, count)
            btnDownloadSelected.text = getString(R.string.gallery_download_selected, count)
            btnDownloadSelected.isEnabled = count > 0
            btnSelectAll.setText(
                if (count == visibleItems.size && visibleItems.isNotEmpty()) {
                    R.string.gallery_clear_selection
                } else {
                    R.string.gallery_select_all
                }
            )
            visibleItems.indices.forEach { i ->
                val isChecked = i in selectedIndices
                checkBoxes.getOrNull(i)?.isChecked = isChecked
                cards.getOrNull(i)?.let { c ->
                    c.strokeWidth = if (isChecked) dp(2) else 0
                    c.strokeColor = primaryColor
                    AccessibilityUtil.setStateDescription(
                        c,
                        getString(if (isChecked) R.string.state_selected else R.string.state_unselected)
                    )
                }
            }
        }

        btnSelectAll.setOnClickListener {
            if (selectedIndices.size == visibleItems.size) {
                selectedIndices.clear()
            } else {
                selectedIndices.addAll(visibleItems.indices)
            }
            syncSelectionUi()
        }

        val listCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        visibleItems.forEachIndexed { idx, media ->
            val card = MaterialCardView(ctx).apply {
                radius = dp(12).toFloat()
                cardElevation = 0f
                strokeColor = primaryColor
                strokeWidth = 0
                minimumHeight = dp(56)
                setContentPadding(dp(8), dp(8), dp(6), dp(8))
                isClickable = true
                isFocusable = true
            }
            val displayTitle = media.title.ifBlank { media.subtitle }
            val typeLabel = getString(if (media.isVideo) R.string.video else R.string.image)
            card.contentDescription = getString(
                R.string.gallery_checkbox_item_desc,
                typeLabel,
                idx + 1,
                visibleItems.size,
                displayTitle,
                media.subtitle
            )
            AccessibilityUtil.setStateDescription(card, getString(R.string.state_unselected))

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val cb = CheckBox(ctx).apply {
                isChecked = false
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(cb, LinearLayout.LayoutParams(dp(36), dp(36)))

            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(if (media.isVideo) R.drawable.ic_video else R.drawable.ic_image)
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
            }
            val t = TextView(ctx).apply {
                text = displayTitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val s = TextView(ctx).apply {
                text = media.subtitle
                alpha = 0.72f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            col.addView(t)
            col.addView(s)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val optionsBtn = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_more)
                if (rippleTyped.resourceId != 0) setBackgroundResource(rippleTyped.resourceId)
                contentDescription = getString(R.string.gallery_item_single_options_desc, displayTitle)
                setOnClickListener {
                    dialog.dismiss()
                    val singleHit = if (media.isVideo) {
                        HitMediaResult(
                            seqId = ++touchSeq,
                            videoUrls = listOf(media.url),
                            postUrl = media.postUrl.ifBlank { currentUrl },
                            pageTitle = media.title,
                            pageThumb = media.thumbUrl
                        )
                    } else {
                        HitMediaResult(
                            seqId = ++touchSeq,
                            imageUrl = media.url,
                            imageWidth = media.width,
                            imageHeight = media.height,
                            imageTitle = media.title,
                            postUrl = media.postUrl.ifBlank { currentUrl },
                            pageTitle = media.title,
                            pageThumb = media.url
                        )
                    }
                    showMediaContextBottomSheet(singleHit)
                }
            }
            row.addView(optionsBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

            card.addView(row)
            card.setOnClickListener {
                if (idx in selectedIndices) {
                    selectedIndices.remove(idx)
                } else {
                    selectedIndices.add(idx)
                }
                syncSelectionUi()
            }

            checkBoxes.add(cb)
            cards.add(card)

            if (media.thumbUrl.startsWith("http") || media.thumbUrl.startsWith("data:image/", ignoreCase = true)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val bmp = ImageDownloadHelper.fetchPreviewBitmap(ctx.applicationContext, media.thumbUrl, currentUrl)
                    if (bmp != null && dialog.isShowing) {
                        iv.setImageBitmap(bmp)
                    }
                }
            }

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            listCol.addView(card, lp)
        }

        val scroll = ScrollView(ctx).apply {
            addView(listCol)
        }
        val scrollLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(320)
        )
        outer.addView(scroll, scrollLp)

        btnDownloadSelected.setOnClickListener {
            val chosen = selectedIndices.sorted().mapNotNull { visibleItems.getOrNull(it) }
            if (chosen.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            queueBatchGalleryDownloads(chosen, selectedImageExt, currentUrl)
        }
        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        }
        outer.addView(btnDownloadSelected, btnLp)

        dialog.setContentView(outer)
        dialog.show()
    }

    private fun queueBatchGalleryDownloads(
        selected: List<GalleryMediaItem>,
        imageContainer: String,
        currentUrl: String
    ) {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val downloadItems = mutableListOf<DownloadItem>()
            val rootDomain = DomainUtil.normalizeRootDomain(currentUrl).orEmpty()
            val domainDisplay = DomainUtil.displayNameForDomain(rootDomain, null)
            val total = selected.size

            selected.forEachIndexed { idx, media ->
                if (media.isVideo) {
                    val singleVideoHit = HitMediaResult(
                        seqId = ++touchSeq,
                        videoUrls = listOf(media.url),
                        postUrl = media.postUrl.ifBlank { currentUrl },
                        pageTitle = media.title,
                        pageThumb = media.thumbUrl
                    )
                    val prepared = buildUnifiedResultItem(ctx, singleVideoHit, currentUrl, DownloadType.video)
                    val isEmptyFeed = BrowserMediaUtil.isFeedOrListingUrl(prepared.resultItem.url) &&
                        prepared.resultItem.formats.isEmpty()
                    if (!isEmptyFeed) {
                        val dwl = withContext(Dispatchers.IO) {
                            downloadViewModel.createDownloadItemFromResult(
                                prepared.resultItem,
                                givenType = DownloadType.video
                            )
                        }
                        downloadItems.add(dwl)
                    }
                } else {
                    val baseTitle = media.title
                        .takeIf { it.isNotBlank() && !BrowserMediaUtil.isGenericPlaceholderTitle(it, domainDisplay) }
                        ?: domainDisplay.ifBlank { "Image" }
                    val uniqueTitle = if (total > 1) "$baseTitle (${idx + 1}/$total)" else baseTitle
                    val res = BrowserMediaUtil.buildImageOnlyResultItem(
                        url = media.url,
                        imageUrl = media.url,
                        title = uniqueTitle,
                        author = domainDisplay,
                        website = rootDomain
                    )
                    val dwl = withContext(Dispatchers.IO) {
                        downloadViewModel.createDownloadItemFromResult(
                            res,
                            givenType = DownloadType.image
                        ).apply {
                            container = imageContainer
                            format.container = imageContainer
                            format.url = media.url
                            thumb = media.url
                        }
                    }
                    downloadItems.add(dwl)
                }
            }

            if (downloadItems.isEmpty()) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.no_image_available, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val queueRes = withContext(Dispatchers.IO) {
                downloadViewModel.queueDownloads(downloadItems)
            }
            if (!isAdded) return@launch
            val queuedCount = (downloadItems.size - queueRes.duplicateDownloadIDs.size).coerceAtLeast(0)
            when {
                queueRes.message.isNotBlank() ->
                    Toast.makeText(requireContext(), queueRes.message, Toast.LENGTH_LONG).show()
                queuedCount > 0 -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.batch_download_started, queuedCount),
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshDownloadedHighlightsOnPage(delayMs = 250L)
                }
            }
        }
    }

    private fun mergeHitWithFallback(hit: HitMediaResult, currentUrl: String): HitMediaResult {
        var resolvedImg = hit.imageUrl
        var resolvedW = hit.imageWidth
        var resolvedH = hit.imageHeight

        if (resolvedImg.isBlank() && hit.postImages.isNotEmpty()) {
            val best = hit.postImages.first()
            resolvedImg = best.url
            resolvedW = best.width
            resolvedH = best.height
        }
        if (resolvedImg.isBlank() && !hit.isLikelyVideo(currentUrl)) {
            val cutoff = System.currentTimeMillis() - 15_000L
            val fallbackImg = detectedImages.values
                .filter { it.capturedAtMs >= cutoff }
                .maxByOrNull { it.capturedAtMs }
            if (fallbackImg != null) {
                resolvedImg = fallbackImg.url
            } else if (BrowserMediaUtil.isLikelyContentImageUrl(currentUrl)) {
                resolvedImg = currentUrl
            }
        }
        if (resolvedImg.isBlank()) {
            resolvedImg = hit.pageThumb
        }
        return hit.copy(
            imageUrl = resolvedImg,
            imageWidth = resolvedW,
            imageHeight = resolvedH
        )
    }

    private fun resolveCanonicalTargetUrl(hit: HitMediaResult, currentUrl: String): String {
        val canonicalPost = BrowserMediaUtil.canonicalizePermalinkUrl(hit.postUrl)
        val canonicalCurrent = BrowserMediaUtil.canonicalizePermalinkUrl(currentUrl)
        return when {
            canonicalPost.startsWith("http") && BrowserMediaUtil.isContentPermalinkUrl(canonicalPost) -> canonicalPost
            canonicalCurrent.startsWith("http") && !BrowserMediaUtil.isFeedOrListingUrl(canonicalCurrent) -> canonicalCurrent
            canonicalPost.startsWith("http") && BrowserMediaUtil.scorePermalinkCandidate(canonicalPost) >= 0 -> canonicalPost
            hit.videoUrls.isNotEmpty() -> hit.videoUrls.first()
            hit.postVideos.isNotEmpty() -> hit.postVideos.first()
            hit.imageUrl.startsWith("http") -> hit.imageUrl
            else -> canonicalCurrent.ifBlank { currentUrl }
        }
    }

    private fun openUnifiedDownloadSheet(rawHit: HitMediaResult, preferredType: DownloadType?) {
        val ctx = context?.applicationContext ?: return
        val currentUrl = webView?.url?.takeIf { it.startsWith("http") } ?: currentPageUrl
        viewLifecycleOwner.lifecycleScope.launch {
            val prepared = buildUnifiedResultItem(ctx, rawHit, currentUrl, preferredType)
            val targetType = preferredType ?: prepared.detectedType
            if (targetType != DownloadType.image &&
                BrowserMediaUtil.isFeedOrListingUrl(prepared.resultItem.url) &&
                prepared.resultItem.formats.isEmpty()
            ) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.browser_open_post_to_download, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            openDownloadSheet(prepared.resultItem, targetType, prepared.forceUpdateData)
        }
    }

    private fun queueQuickImageDownload(rawHit: HitMediaResult) {
        val ctx = context?.applicationContext ?: return
        val currentUrl = webView?.url?.takeIf { it.startsWith("http") } ?: currentPageUrl
        val merged = mergeHitWithFallback(rawHit, currentUrl)
        val imageTarget = merged.imageUrl.ifBlank { merged.pageThumb }
        if (imageTarget.isBlank()) {
            Toast.makeText(requireContext(), R.string.no_image_available, Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val prepared = buildUnifiedResultItem(ctx, merged, currentUrl, DownloadType.image)
            val queueRes = withContext(Dispatchers.IO) {
                val downloadItem = downloadViewModel.createDownloadItemFromResult(
                    prepared.resultItem,
                    givenType = DownloadType.image
                ).apply {
                    format.url = imageTarget
                    thumb = imageTarget
                }
                downloadViewModel.queueDownloads(listOf(downloadItem))
            }
            if (!isAdded) return@launch
            when {
                queueRes.message.isNotBlank() ->
                    Toast.makeText(requireContext(), queueRes.message, Toast.LENGTH_LONG).show()
                queueRes.duplicateDownloadIDs.isEmpty() -> {
                    Toast.makeText(requireContext(), R.string.image_download_started, Toast.LENGTH_SHORT).show()
                    refreshDownloadedHighlightsOnPage(delayMs = 250L)
                }
            }
        }
    }

    private fun queueQuickVideoDownload(rawHit: HitMediaResult) {
        val ctx = context?.applicationContext ?: return
        val currentUrl = webView?.url?.takeIf { it.startsWith("http") } ?: currentPageUrl
        viewLifecycleOwner.lifecycleScope.launch {
            val prepared = buildUnifiedResultItem(ctx, rawHit, currentUrl, DownloadType.video)
            if (BrowserMediaUtil.isFeedOrListingUrl(prepared.resultItem.url) &&
                prepared.resultItem.formats.isEmpty()
            ) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.browser_open_post_to_download, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val queueRes = withContext(Dispatchers.IO) {
                val downloadItem = downloadViewModel.createDownloadItemFromResult(
                    prepared.resultItem,
                    givenType = DownloadType.video
                )
                downloadViewModel.queueDownloads(listOf(downloadItem))
            }
            if (!isAdded) return@launch
            when {
                queueRes.message.isNotBlank() ->
                    Toast.makeText(requireContext(), queueRes.message, Toast.LENGTH_LONG).show()
                queueRes.duplicateDownloadIDs.isEmpty() -> {
                    Toast.makeText(requireContext(), R.string.browser_quick_download_started, Toast.LENGTH_SHORT).show()
                    refreshDownloadedHighlightsOnPage(delayMs = 250L)
                }
            }
        }
    }

    private fun openDownloadSheet(
        resultItem: ResultItem,
        downloadType: DownloadType,
        forceUpdateData: Boolean = false
    ) {
        if (!isAdded) return
        downloadCardViewModel.setResultItem(resultItem)
        downloadCardViewModel.setDownloadItem(null)
        findNavController().navigate(
            R.id.action_browserFragment_to_downloadBottomSheetDialog,
            bundleOf(
                "type" to downloadType,
                "force_update_data" to forceUpdateData
            )
        )
    }

    private suspend fun buildUnifiedResultItem(
        ctx: Context,
        rawHit: HitMediaResult,
        currentUrl: String,
        preferredType: DownloadType? = null
    ): PreparedBrowserDownload {
        val hit = mergeHitWithFallback(rawHit, currentUrl)
        val resolvedPageOrPost = resolveCanonicalTargetUrl(hit, currentUrl)
        val detectedType = when {
            hit.isLikelyVideo(resolvedPageOrPost) || hit.isLikelyVideo(currentUrl) -> DownloadType.video
            BrowserMediaUtil.isDirectStaticImageFile(resolvedPageOrPost) -> DownloadType.image
            hit.imageUrl.isNotBlank() -> DownloadType.image
            else -> downloadViewModel.getDownloadType(url = resolvedPageOrPost)
        }
        val effectiveType = preferredType ?: detectedType
        val isDirectStaticImage = BrowserMediaUtil.isDirectStaticImageFile(resolvedPageOrPost)
        val isDirectMediaStream = FallbackMediaRules.classifyMediaCandidate(resolvedPageOrPost) != null
        val isFeedTarget = BrowserMediaUtil.isFeedOrListingUrl(resolvedPageOrPost)
        val shouldForceUpdateData =
            effectiveType != DownloadType.image && !isDirectMediaStream && !isDirectStaticImage && !isFeedTarget

        val resultItem = withContext(Dispatchers.IO) {
            runCatching { CookieManager.getInstance().flush() }
            SiteSessionManager.prepareSessionContextForUrl(ctx, resolvedPageOrPost)

            val base = downloadViewModel.createEmptyResultItem(resolvedPageOrPost)
            val domainName = DomainUtil.displayNameForDomain(
                DomainUtil.normalizeRootDomain(resolvedPageOrPost).orEmpty(),
                null
            )
            val cleanTitle = listOf(base.title, hit.imageTitle, hit.pageTitle)
                .firstOrNull { it.isNotBlank() && !BrowserMediaUtil.isGenericPlaceholderTitle(it, domainName) }
                .orEmpty()
            val resolvedThumb = hit.imageUrl.ifBlank { hit.pageThumb }.ifBlank { base.thumb }

            if (effectiveType == DownloadType.image) {
                val imageTitle = cleanTitle.ifBlank { domainName }.ifBlank { "Image" }
                val imageAuthor = base.author.ifBlank { domainName }
                return@withContext base.copy(
                    title = imageTitle,
                    author = imageAuthor,
                    thumb = resolvedThumb
                )
            }

            if (!isDirectMediaStream && !isDirectStaticImage && !isFeedTarget) {
                // Webpage permalink: leave title/author/formats blank so yt-dlp (and fallback) resolves real metadata and streams
                return@withContext base.copy(
                    title = "",
                    author = "",
                    thumb = resolvedThumb,
                    formats = arrayListOf()
                )
            }

            val canUseNetworkStreams = !BrowserMediaUtil.isFeedOrListingUrl(currentUrl)
            val scopedVideos = (hit.videoUrls + hit.postVideos).distinct()
            val prewarmFormats = if (!resolvedPageOrPost.isYoutubeURL() &&
                (scopedVideos.isNotEmpty() || (canUseNetworkStreams && detectedMedia.isNotEmpty()))
            ) {
                val combined = mutableListOf<FallbackMediaRules.MediaCandidate>()
                if (canUseNetworkStreams) {
                    combined.addAll(detectedMedia.values)
                }
                val headers = buildMap {
                    val ua = currentPageUa.ifBlank { defaultMobileUa }
                    if (ua.isNotBlank()) put("User-Agent", ua)
                    put("Referer", currentUrl.ifBlank { resolvedPageOrPost })
                    SiteSessionManager.getCookieHeaderForUrl(resolvedPageOrPost)?.let { put("Cookie", it) }
                        ?: SiteSessionManager.getCookieHeaderForUrl(currentUrl)?.let { put("Cookie", it) }
                }
                scopedVideos.forEach { domUrl ->
                    FallbackMediaRules.classifyMediaCandidate(
                        url = domUrl,
                        headers = headers,
                        source = "browser_dom"
                    )?.let { combined.add(it) }
                }
                val deduped = FallbackMediaRules.deduplicateCandidates(combined)
                val validForTarget = if (effectiveType == DownloadType.audio) {
                    deduped
                } else {
                    if (deduped.any { it.kind != FallbackMediaRules.StreamKind.AUDIO_ONLY }) deduped else emptyList()
                }
                if (validForTarget.isNotEmpty()) {
                    LocalFallbackEngine.candidatesToFormats(validForTarget)
                } else {
                    arrayListOf()
                }
            } else {
                arrayListOf()
            }

            val fallbackTitle = cleanTitle.ifBlank { domainName }.ifBlank { "Media" }
            val fallbackAuthor = base.author.ifBlank { domainName }
            base.copy(
                title = fallbackTitle,
                author = fallbackAuthor,
                thumb = resolvedThumb,
                formats = if (prewarmFormats.isNotEmpty()) prewarmFormats else base.formats
            )
        }

        return PreparedBrowserDownload(
            resultItem = resultItem,
            detectedType = detectedType,
            forceUpdateData = shouldForceUpdateData
        )
    }

    private suspend fun inspectMediaAtPoint(normX: Float, normY: Float, seqId: Long): HitMediaResult {
        val wv = webView ?: return HitMediaResult(seqId = seqId)
        val ctx = context ?: return HitMediaResult(seqId = seqId)
        val script = BrowserScriptLoader.buildInspectMediaScript(ctx, normX, normY)
        return suspendCancellableCoroutine { cont ->
            val raw = runCatching {
                wv.evaluateJavascript(script) { result ->
                    if (!cont.isActive) return@evaluateJavascript
                    cont.resume(parseHitMediaResult(result, seqId))
                }
            }
            if (raw.isFailure && cont.isActive) {
                cont.resume(HitMediaResult(seqId = seqId))
            }
        }
    }

    private fun parseHitMediaResult(rawJson: String?, seqId: Long): HitMediaResult {
        if (rawJson.isNullOrBlank() || rawJson == "null") return HitMediaResult(seqId = seqId)
        return runCatching {
            val unescaped = unescapeJsJsonString(rawJson) ?: return HitMediaResult(seqId = seqId)
            val obj = JSONObject(unescaped)

            val pointVideos = mutableListOf<String>()
            obj.optJSONArray("videos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i).trim()
                    if (u.startsWith("http")) pointVideos.add(u)
                }
            }

            val postVideos = mutableListOf<String>()
            obj.optJSONArray("postVideos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i).trim()
                    if (u.startsWith("http")) postVideos.add(u)
                }
            }

            val postImages = mutableListOf<DetectedImageEntry>()
            obj.optJSONArray("postImages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val itemObj = arr.optJSONObject(i) ?: continue
                    val u = itemObj.optString("url").trim()
                    if (BrowserMediaUtil.isLikelyContentImageUrl(u) || u.startsWith("data:image/", true)) {
                        postImages.add(
                            DetectedImageEntry(
                                url = u,
                                width = itemObj.optInt("w", 0),
                                height = itemObj.optInt("h", 0),
                                title = itemObj.optString("title").trim(),
                                postUrl = BrowserMediaUtil.canonicalizePermalinkUrl(itemObj.optString("postUrl").trim()),
                                source = itemObj.optString("src", "dom")
                            )
                        )
                    }
                }
            }

            val pointImageUrl = obj.optString("imageUrl").trim()
                .takeIf { BrowserMediaUtil.isLikelyContentImageUrl(it) || it.startsWith("data:image/", true) }
                .orEmpty()

            HitMediaResult(
                seqId = seqId,
                imageUrl = pointImageUrl,
                imageWidth = obj.optInt("imageW", 0),
                imageHeight = obj.optInt("imageH", 0),
                imageTitle = obj.optString("imageTitle").trim(),
                videoUrls = pointVideos.distinct(),
                hasVideoElement = obj.optBoolean("hasVideoEl", false),
                postUrl = BrowserMediaUtil.canonicalizePermalinkUrl(obj.optString("postUrl").trim()),
                pageTitle = obj.optString("pageTitle").trim(),
                pageThumb = obj.optString("pageThumb").trim(),
                postImages = postImages.distinctBy { it.url },
                postVideos = (pointVideos + postVideos).distinct()
            )
        }.getOrDefault(HitMediaResult(seqId = seqId))
    }

    private fun unescapeJsJsonString(rawJson: String?): String? {
        if (rawJson.isNullOrBlank() || rawJson == "null") return null
        return if (rawJson.startsWith("\"")) {
            org.json.JSONTokener(rawJson).nextValue().toString()
        } else {
            rawJson
        }
    }

    private fun buildProfileScopeKey(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url
        val host = uri.host?.lowercase(Locale.US)
            ?.removePrefix("www.")
            ?.removePrefix("m.")
            ?.removePrefix("mobile.")
            .orEmpty()
        if (host.isBlank()) return url
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        if (host == "youtube.com") {
            val first = segments.firstOrNull()?.lowercase(Locale.US).orEmpty()
            if (first == "results" || first == "playlist") {
                return "$host/$first?${uri.rawQuery.orEmpty()}"
            }
        }
        return "$host/${segments.take(2).joinToString("/")}"
    }

    private suspend fun loadMediaHistoryIndex(appCtx: Context): MediaHistoryMatcher.MediaHistoryIndex =
        withContext(Dispatchers.IO) {
            val db = DBManager.getInstance(appCtx)
            MediaHistoryMatcher.MediaHistoryIndex(
                downloadedUrls = db.historyDao.getAllUrls(),
                inProgressUrls = db.downloadDao.getInProgressUrls()
            )
        }

    private suspend fun scanRawPageMediaItems(wv: WebView, ctx: Context): List<ScannedPageMediaItem> {
        val script = BrowserScriptLoader.buildChannelScannerScript(ctx)
        return suspendCancellableCoroutine { cont ->
            val call = runCatching {
                wv.evaluateJavascript(script) { raw ->
                    if (!cont.isActive) return@evaluateJavascript
                    cont.resume(parseScannedPageMediaItems(raw))
                }
            }
            if (call.isFailure && cont.isActive) {
                cont.resume(emptyList())
            }
        }
    }

    private fun parseScannedPageMediaItems(rawJson: String?): List<ScannedPageMediaItem> {
        val unescaped = runCatching { unescapeJsJsonString(rawJson) }.getOrNull() ?: return emptyList()
        return runCatching {
            val obj = JSONObject(unescaped)
            val pageAuthor = obj.optString("pageAuthor").trim()
            val arr = obj.optJSONArray("items") ?: return@runCatching emptyList()
            val out = ArrayList<ScannedPageMediaItem>(arr.length())
            for (i in 0 until arr.length()) {
                val itemObj = arr.optJSONObject(i) ?: continue
                val url = itemObj.optString("url").trim()
                if (!url.startsWith("http")) continue
                val key = itemObj.optString("key").trim().ifBlank { MediaHistoryMatcher.primaryMediaKey(url) }
                out.add(
                    ScannedPageMediaItem(
                        key = key,
                        url = url,
                        title = itemObj.optString("title").trim(),
                        thumb = itemObj.optString("thumb").trim(),
                        duration = itemObj.optString("duration").trim(),
                        author = itemObj.optString("author").trim().ifBlank { pageAuthor },
                        isVideo = itemObj.optBoolean("isVideo", true)
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun mergeScannedBucket(
        currentUrl: String,
        freshItems: List<ScannedPageMediaItem>
    ): LinkedHashMap<String, ScannedPageMediaItem> {
        val profileKey = buildProfileScopeKey(currentUrl)
        val bucket = scannedItemsByProfile.getOrPut(profileKey) { LinkedHashMap() }
        freshItems.forEach { item ->
            val prev = bucket[item.key]
            bucket[item.key] = if (prev == null) item else item.copy(
                title = item.title.ifBlank { prev.title },
                thumb = item.thumb.ifBlank { prev.thumb },
                duration = item.duration.ifBlank { prev.duration },
                author = item.author.ifBlank { prev.author }
            )
        }
        while (scannedItemsByProfile.size > MAX_CACHED_PROFILES) {
            val oldestKey = scannedItemsByProfile.keys.firstOrNull() ?: break
            scannedItemsByProfile.remove(oldestKey)
        }
        return bucket
    }

    private suspend fun scanCurrentPageMediaItems(): List<ScannedPageMediaItem> {
        val wv = webView ?: return emptyList()
        val ctx = context?.applicationContext ?: return emptyList()
        val currentUrl = wv.url?.takeIf { it.startsWith("http") } ?: currentPageUrl
        val freshItems = scanRawPageMediaItems(wv, ctx)
        val bucket = mergeScannedBucket(currentUrl, freshItems)

        val combined = bucket.values.toList().ifEmpty { freshItems }
        if (combined.isEmpty()) return emptyList()
        val index = loadMediaHistoryIndex(ctx)
        return combined.map { it.copy(state = index.getState(it.url)) }
    }

    private fun refreshDownloadedHighlightsOnPage(delayMs: Long = 0L) {
        if (!isAdded) return
        highlightRefreshJob?.cancel()
        highlightRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            if (delayMs > 0L) delay(delayMs)
            val wv = webView ?: return@launch
            val ctx = context?.applicationContext ?: return@launch
            if (!isAdded || !wv.isVisible) return@launch
            val currentUrl = wv.url?.takeIf { it.startsWith("http") } ?: currentPageUrl
            if (!currentUrl.startsWith("http")) return@launch

            val freshItems = scanRawPageMediaItems(wv, ctx)
            if (freshItems.isNotEmpty()) {
                mergeScannedBucket(currentUrl, freshItems)
            }
            val candidateUrls = (freshItems.map { it.url } + listOf(currentUrl)).distinct()
            val index = loadMediaHistoryIndex(ctx)
            val (downloadedKeys, inProgressKeys) = index.matchedKeysFor(candidateUrls)

            val script = BrowserScriptLoader.buildHighlightDownloadedScript(
                context = ctx,
                downloadedKeys = downloadedKeys,
                inProgressKeys = inProgressKeys,
                downloadedLabel = getString(R.string.browser_badge_downloaded),
                inProgressLabel = getString(R.string.browser_badge_in_progress)
            )
            wv.evaluateJavascript(script, null)
        }
    }

    private fun openChannelMultiSelectSheet() {
        val currentUrl = webView?.url?.takeIf { it.startsWith("http") } ?: browserViewModel.currentUrl.value
        if (currentUrl.isNullOrBlank() || !currentUrl.startsWith("http")) return
        viewLifecycleOwner.lifecycleScope.launch {
            val scanned = scanCurrentPageMediaItems()
            if (!isAdded) return@launch
            if (scanned.isEmpty()) {
                val hit = inspectMediaAtPoint(-1f, -1f, ++touchSeq)
                val postGallery = collectPostGalleryItems(hit, currentUrl)
                if (postGallery.size > 1) {
                    showAllDetectedMediaSheet(postGallery, currentUrl)
                } else {
                    Toast.makeText(requireContext(), R.string.browser_batch_select_empty, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            refreshDownloadedHighlightsOnPage(delayMs = 0L)
            showChannelMultiSelectSheet(currentUrl, scanned)
        }
    }

    private fun showChannelMultiSelectSheet(
        initialUrl: String,
        initialItems: List<ScannedPageMediaItem>
    ) {
        if (!isAdded) return
        val ctx = requireContext()
        activeContextDialog?.dismiss()
        val dialog = BottomSheetDialog(ctx)
        activeContextDialog = dialog

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val primaryColor = MaterialColors.getColor(
            ctx,
            androidx.appcompat.R.attr.colorPrimary,
            0xFF6750A4.toInt()
        )
        val rippleTyped = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleTyped, true)

        var allItems = initialItems
        val selectedKeys = linkedSetOf<String>()
        var onlyNewFilter = false
        var selectedPresetId = DownloadPresetManager.getLastUsedBatchPresetId(ctx)
        val allPresets = DownloadPresetManager.getAllPresets(ctx)

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        AccessibilityUtil.setHeading(headerTitle, true)
        headerRow.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rescanBtn = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_refresh)
            if (rippleTyped.resourceId != 0) setBackgroundResource(rippleTyped.resourceId)
            contentDescription = getString(R.string.browser_batch_rescan_desc)
        }
        headerRow.addView(rescanBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        outer.addView(headerRow)

        val summaryRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        val summaryTv = TextView(ctx).apply {
            alpha = 0.78f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
        }
        summaryRow.addView(summaryTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val selectedCountTv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
            setPadding(dp(8), 0, 0, 0)
        }
        summaryRow.addView(selectedCountTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(summaryRow)

        val controlsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val radiusPx = 18f * density
        val onlyNewChip = Chip(ctx).apply {
            text = getString(R.string.browser_batch_only_new)
            contentDescription = getString(R.string.browser_batch_only_new)
            isCheckable = true
            isChecked = false
            chipCornerRadius = radiusPx
            AccessibilityUtil.setStateDescription(this, getString(R.string.state_unselected))
        }
        controlsRow.addView(onlyNewChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        controlsRow.addView(
            View(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        val btnSelectNew = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.browser_batch_select_new)
            minHeight = dp(38)
            maxLines = 1
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        controlsRow.addView(btnSelectNew, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(controlsRow)

        val presetsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(8))
        }
        val presetGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = true
        }
        val autoChip = Chip(ctx).apply {
            id = View.generateViewId()
            tag = DownloadPresetManager.AUTO_BATCH_PRESET_ID
            text = getString(R.string.preset_auto)
            contentDescription = getString(R.string.select_option_desc, getString(R.string.preset_auto))
            isCheckable = true
            isCheckedIconVisible = false
            chipCornerRadius = radiusPx
            isChecked = selectedPresetId == DownloadPresetManager.AUTO_BATCH_PRESET_ID
            setOnClickListener {
                selectedPresetId = DownloadPresetManager.AUTO_BATCH_PRESET_ID
                DownloadPresetManager.setLastUsedBatchPresetId(ctx, selectedPresetId)
            }
        }
        presetGroup.addView(autoChip)

        allPresets.forEach { preset ->
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                tag = preset.id
                text = preset.name
                contentDescription = getString(R.string.preset_apply_desc, preset.name)
                isCheckable = true
                isCheckedIconVisible = false
                chipCornerRadius = radiusPx
                isChecked = selectedPresetId == preset.id
                setOnClickListener {
                    selectedPresetId = preset.id
                    DownloadPresetManager.setLastUsedBatchPresetId(ctx, selectedPresetId)
                }
            }
            presetGroup.addView(chip)
        }
        if (presetGroup.checkedChipId == View.NO_ID) {
            autoChip.isChecked = true
            selectedPresetId = DownloadPresetManager.AUTO_BATCH_PRESET_ID
        }
        presetsScroll.addView(presetGroup)
        outer.addView(presetsScroll)

        lateinit var syncBatchUi: () -> Unit
        val adapter = ChannelBatchAdapter(
            dialog = dialog,
            pageRefererUrl = initialUrl,
            primaryColor = primaryColor,
            rippleResId = rippleTyped.resourceId,
            onToggleItem = { media ->
                if (media.key in selectedKeys) selectedKeys.remove(media.key) else selectedKeys.add(media.key)
                syncBatchUi()
            },
            onOpenSingleOptions = { media ->
                dialog.dismiss()
                val singleHit = HitMediaResult(
                    seqId = ++touchSeq,
                    imageUrl = if (!media.isVideo) media.thumb else "",
                    postUrl = media.url,
                    pageTitle = media.title,
                    pageThumb = media.thumb,
                    hasVideoElement = media.isVideo
                )
                openUnifiedDownloadSheet(singleHit, preferredType = null)
            }
        )

        val recycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
        }
        val listHeightPx = (resources.displayMetrics.heightPixels * 0.36f).toInt().coerceIn(dp(180), dp(290))
        outer.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeightPx))

        val footerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnAdvanced = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.browser_batch_advanced)
            isEnabled = false
            minimumHeight = dp(48)
        }
        val btnDownloadSelected = MaterialButton(ctx).apply {
            text = getString(R.string.gallery_download_selected, 0)
            isEnabled = false
            minimumHeight = dp(48)
        }
        footerRow.addView(btnAdvanced, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })
        footerRow.addView(btnDownloadSelected, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(footerRow)

        syncBatchUi = {
            val newCount = allItems.count { it.state == MediaHistoryMatcher.MediaDownloadState.NEW }
            val dlCount = allItems.count { it.state == MediaHistoryMatcher.MediaDownloadState.DOWNLOADED }
            val progCount = allItems.count { it.state == MediaHistoryMatcher.MediaDownloadState.IN_PROGRESS }
            headerTitle.text = "${getString(R.string.browser_batch_select_title)} (${allItems.size})"
            summaryTv.text = getString(R.string.browser_batch_summary, newCount, dlCount, progCount)

            val displayed = if (onlyNewFilter) {
                allItems.filter { it.state == MediaHistoryMatcher.MediaDownloadState.NEW }
            } else {
                allItems
            }
            adapter.submit(displayed, selectedKeys)

            val selCount = selectedKeys.size
            selectedCountTv.text = getString(R.string.gallery_selected_count, selCount)
            btnDownloadSelected.text = getString(R.string.gallery_download_selected, selCount)
            btnDownloadSelected.isEnabled = selCount > 0
            btnAdvanced.isEnabled = selCount > 0

            val selectableNewKeys = displayed.filter { it.state == MediaHistoryMatcher.MediaDownloadState.NEW }.map { it.key }
            val allNewSelected = selectableNewKeys.isNotEmpty() && selectableNewKeys.all { it in selectedKeys }
            btnSelectNew.setText(if (allNewSelected) R.string.gallery_clear_selection else R.string.browser_batch_select_new)
            btnSelectNew.isEnabled = selectableNewKeys.isNotEmpty() || selectedKeys.isNotEmpty()
        }

        onlyNewChip.setOnCheckedChangeListener { _, isChecked ->
            onlyNewFilter = isChecked
            AccessibilityUtil.setStateDescription(
                onlyNewChip,
                getString(if (isChecked) R.string.state_selected else R.string.state_unselected)
            )
            syncBatchUi()
        }

        btnSelectNew.setOnClickListener {
            val displayed = if (onlyNewFilter) {
                allItems.filter { it.state == MediaHistoryMatcher.MediaDownloadState.NEW }
            } else {
                allItems
            }
            val selectableNewKeys = displayed.filter { it.state == MediaHistoryMatcher.MediaDownloadState.NEW }.map { it.key }
            val allNewSelected = selectableNewKeys.isNotEmpty() && selectableNewKeys.all { it in selectedKeys }
            if (allNewSelected) {
                selectedKeys.removeAll(selectableNewKeys.toSet())
            } else if (selectableNewKeys.isNotEmpty()) {
                selectedKeys.addAll(selectableNewKeys)
            } else {
                selectedKeys.clear()
            }
            syncBatchUi()
        }

        rescanBtn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val rescanned = scanCurrentPageMediaItems()
                if (!isAdded || !dialog.isShowing) return@launch
                if (rescanned.isNotEmpty()) {
                    allItems = rescanned
                    val validKeys = allItems.map { it.key }.toSet()
                    selectedKeys.retainAll(validKeys)
                    syncBatchUi()
                    refreshDownloadedHighlightsOnPage(delayMs = 0L)
                }
            }
        }

        btnDownloadSelected.setOnClickListener {
            val chosen = allItems.filter { it.key in selectedKeys }
            if (chosen.isEmpty()) return@setOnClickListener
            val preset = allPresets.firstOrNull { it.id == selectedPresetId }
            dialog.dismiss()
            queueBatchScannedItems(chosen, preset, initialUrl)
        }

        btnAdvanced.setOnClickListener {
            val chosen = allItems.filter { it.key in selectedKeys }
            if (chosen.isEmpty()) return@setOnClickListener
            val preset = allPresets.firstOrNull { it.id == selectedPresetId }
            dialog.dismiss()
            openBatchAdvancedSheet(chosen, preset, initialUrl)
        }

        syncBatchUi()
        dialog.setContentView(outer)
        dialog.setOnShowListener {
            runCatching {
                dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                dialog.behavior.skipCollapsed = true
            }
        }
        dialog.show()
    }

    private suspend fun buildBatchDownloadItemsFromScanned(
        appCtx: Context,
        selected: List<ScannedPageMediaItem>,
        preset: DownloadPresetManager.DownloadPreset?,
        currentUrl: String
    ): MutableList<DownloadItem> = withContext(Dispatchers.IO) {
        runCatching { CookieManager.getInstance().flush() }
        SiteSessionManager.prepareSessionContextForUrl(appCtx, currentUrl)
        val items = mutableListOf<DownloadItem>()
        selected.forEachIndexed { idx, media ->
            SiteSessionManager.prepareSessionContextForUrl(appCtx, media.url)
            val result = downloadViewModel.createEmptyResultItem(media.url).copy(
                title = media.title.ifBlank { media.author.ifBlank { "Media" } },
                author = media.author,
                duration = media.duration,
                thumb = media.thumb
            )
            val resolvedType = preset?.downloadType() ?: if (media.isVideo) {
                DownloadType.video
            } else {
                downloadViewModel.getDownloadType(url = media.url)
            }
            val dwl = downloadViewModel.createDownloadItemFromResult(
                result = result,
                givenType = resolvedType
            ).apply {
                rowNumber = idx + 1
                if (preset != null) {
                    DownloadPresetManager.applyPresetToDownloadItem(this, preset)
                }
            }
            items.add(dwl)
        }
        items
    }

    private fun queueBatchScannedItems(
        selected: List<ScannedPageMediaItem>,
        preset: DownloadPresetManager.DownloadPreset?,
        currentUrl: String
    ) {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val items = buildBatchDownloadItemsFromScanned(ctx, selected, preset, currentUrl)
            if (items.isEmpty()) return@launch
            val allowDuplicates = selected.any { it.state != MediaHistoryMatcher.MediaDownloadState.NEW }
            val queueRes = withContext(Dispatchers.IO) {
                downloadViewModel.queueDownloads(items, ignoreDuplicates = allowDuplicates)
            }
            if (!isAdded) return@launch
            val queuedCount = (items.size - queueRes.duplicateDownloadIDs.size).coerceAtLeast(0)
            when {
                queueRes.message.isNotBlank() ->
                    Toast.makeText(requireContext(), queueRes.message, Toast.LENGTH_LONG).show()
                queuedCount > 0 ->
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.batch_download_started, queuedCount),
                        Toast.LENGTH_SHORT
                    ).show()
            }
            refreshDownloadedHighlightsOnPage(delayMs = 250L)
        }
    }

    private fun openBatchAdvancedSheet(
        selected: List<ScannedPageMediaItem>,
        preset: DownloadPresetManager.DownloadPreset?,
        currentUrl: String
    ) {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val items = buildBatchDownloadItemsFromScanned(ctx, selected, preset, currentUrl)
            if (items.isEmpty()) return@launch
            downloadViewModel.insertToProcessing(items).join()
            if (!isAdded) return@launch
            runCatching {
                findNavController().navigate(R.id.action_browserFragment_to_downloadMultipleBottomSheetDialog2)
            }
        }
    }

    private inner class ChannelBatchAdapter(
        private val dialog: BottomSheetDialog,
        private val pageRefererUrl: String,
        private val primaryColor: Int,
        private val rippleResId: Int,
        private val onToggleItem: (ScannedPageMediaItem) -> Unit,
        private val onOpenSingleOptions: (ScannedPageMediaItem) -> Unit
    ) : RecyclerView.Adapter<ChannelBatchAdapter.BatchViewHolder>() {

        private var items: List<ScannedPageMediaItem> = emptyList()
        private var checkedKeys: Set<String> = emptySet()

        fun submit(nextItems: List<ScannedPageMediaItem>, selected: Set<String>) {
            items = nextItems
            checkedKeys = selected.toSet()
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BatchViewHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val card = MaterialCardView(ctx).apply {
                radius = dp(12).toFloat()
                cardElevation = 0f
                strokeColor = primaryColor
                minimumHeight = dp(60)
                setContentPadding(dp(8), dp(8), dp(6), dp(8))
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cb = CheckBox(ctx).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(cb, LinearLayout.LayoutParams(dp(34), dp(34)))

            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(64), dp(46)))

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
            }
            val titleTv = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val metaRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, 0)
            }
            val statusTv = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }
            val metaTv = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                alpha = 0.72f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), 0, 0, 0)
            }
            metaRow.addView(statusTv)
            metaRow.addView(metaTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(titleTv)
            col.addView(metaRow)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val optionsBtn = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_more)
                if (rippleResId != 0) setBackgroundResource(rippleResId)
            }
            row.addView(optionsBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

            card.addView(row)
            return BatchViewHolder(card, cb, iv, titleTv, statusTv, metaTv, optionsBtn, density)
        }

        override fun onBindViewHolder(holder: BatchViewHolder, position: Int) {
            holder.bind(items[position], position, items.size)
        }

        inner class BatchViewHolder(
            private val card: MaterialCardView,
            private val cb: CheckBox,
            private val iv: ImageView,
            private val titleTv: TextView,
            private val statusTv: TextView,
            private val metaTv: TextView,
            private val optionsBtn: ImageButton,
            private val density: Float
        ) : RecyclerView.ViewHolder(card) {

            fun bind(media: ScannedPageMediaItem, pos: Int, total: Int) {
                val ctx = card.context
                val isChecked = media.key in checkedKeys
                cb.isChecked = isChecked
                card.strokeWidth = if (isChecked) (2f * density).toInt() else 0

                titleTv.text = media.title.ifBlank { media.url }
                val (statusText, pillColor, textColor) = when (media.state) {
                    MediaHistoryMatcher.MediaDownloadState.DOWNLOADED -> Triple(
                        ctx.getString(R.string.browser_batch_status_downloaded),
                        Color.parseColor("#1B873F"),
                        Color.WHITE
                    )
                    MediaHistoryMatcher.MediaDownloadState.IN_PROGRESS -> Triple(
                        ctx.getString(R.string.browser_batch_status_in_progress),
                        Color.parseColor("#B45309"),
                        Color.WHITE
                    )
                    MediaHistoryMatcher.MediaDownloadState.NEW -> Triple(
                        ctx.getString(R.string.browser_batch_status_new),
                        MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY),
                        MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
                    )
                }

                statusTv.text = statusText
                statusTv.setTextColor(textColor)
                statusTv.background = GradientDrawable().apply {
                    cornerRadius = 10f * density
                    setColor(pillColor)
                }

                val extraInfo = listOf(media.duration, media.author).filter { it.isNotBlank() }.joinToString(" · ")
                metaTv.text = extraInfo
                metaTv.isVisible = extraInfo.isNotBlank()

                card.contentDescription = ctx.getString(
                    R.string.browser_batch_item_desc,
                    pos + 1,
                    total,
                    titleTv.text.toString(),
                    statusText,
                    extraInfo
                )
                AccessibilityUtil.setStateDescription(
                    card,
                    ctx.getString(if (isChecked) R.string.state_selected else R.string.state_unselected)
                )

                card.setOnClickListener { onToggleItem(media) }
                optionsBtn.contentDescription = ctx.getString(R.string.gallery_item_single_options_desc, titleTv.text.toString())
                optionsBtn.setOnClickListener { onOpenSingleOptions(media) }

                iv.setImageResource(if (media.isVideo) R.drawable.ic_video else R.drawable.ic_image)
                iv.tag = media.thumb
                if (media.thumb.startsWith("http")) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val bmp = ImageDownloadHelper.fetchPreviewBitmap(ctx.applicationContext, media.thumb, pageRefererUrl)
                        if (bmp != null && dialog.isShowing && iv.tag == media.thumb) {
                            iv.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }
    }

    fun focusUrlBar() {
        if (!isAdded) return
        if (!quickPlatformsScroll.isVisible) {
            quickPlatformsScroll.isVisible = true
        }
        urlInput.requestFocus()
        urlInput.selectAll()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(urlInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runCatching { webView?.saveState(outState) }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        syncWebViewAccessibilityMode()
        val pending = browserViewModel.consumePendingUrl()
        if (!pending.isNullOrBlank()) {
            loadAddress(pending)
        } else if (currentPageUrl.startsWith("http") && webView?.isVisible == true) {
            refreshDownloadedHighlightsOnPage(delayMs = 300L)
        }
    }

    override fun onPause() {
        runCatching {
            webView?.evaluateJavascript(
                "document.querySelectorAll('video,audio').forEach(function(e){try{e.pause();}catch(x){}});",
                null
            )
            CookieManager.getInstance().flush()
        }
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        highlightRefreshJob?.cancel()
        highlightRefreshJob = null
        (activity as? MainActivity)?.apply {
            exitWebFullscreen()
            setBrowserBottomNavHidden(false)
        }
        accessibilityManager?.removeAccessibilityStateChangeListener(accessibilityStateListener)
        accessibilityManager?.removeTouchExplorationStateChangeListener(touchExplorationListener)
        accessibilityManager = null
        activeContextDialog?.dismiss()
        activeContextDialog = null
        runCatching { CookieManager.getInstance().flush() }
        webView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        private const val MAX_CACHED_IMAGES = 8
        private const val MAX_CACHED_VIDEOS = 10
        private const val MAX_CACHED_PROFILES = 5
    }
}

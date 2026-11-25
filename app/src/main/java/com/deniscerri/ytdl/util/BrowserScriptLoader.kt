package com.deniscerri.ytdl.util

import android.content.Context
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object BrowserScriptLoader {

    private const val MEDIA_INSPECTOR_ASSET = "browser_media_inspector.js"
    private const val ICON_INSPECTOR_ASSET = "browser_icon_inspector.js"
    private const val CHANNEL_SCANNER_ASSET = "browser_channel_scanner.js"

    @Volatile
    private var cachedMediaTemplate: String? = null

    @Volatile
    private var cachedIconScript: String? = null

    @Volatile
    private var cachedChannelScannerTemplate: String? = null

    fun buildInspectMediaScript(context: Context, normX: Float, normY: Float): String {
        val template = loadAssetCached(context, MEDIA_INSPECTOR_ASSET)
            ?: return EMPTY_MEDIA_RESULT_JS
        val nx = String.format(Locale.US, "%.4f", normX)
        val ny = String.format(Locale.US, "%.4f", normY)
        val cfgJson = WebRulesRegistry.toBrowserJsConfigJson()
        return template
            .replace("__NX__", nx)
            .replace("__NY__", ny)
            .replace("__CFG_JSON__", cfgJson)
    }

    fun getIconInspectorScript(context: Context): String {
        return loadAssetCached(context, ICON_INSPECTOR_ASSET)
            ?: EMPTY_ICON_RESULT_JS
    }

    fun buildChannelScannerScript(context: Context): String {
        val template = loadAssetCached(context, CHANNEL_SCANNER_ASSET)
            ?: return EMPTY_CHANNEL_SCAN_JS
        return template
            .replace("__MODE__", JSONObject.quote("scan"))
            .replace("__CFG_JSON__", WebRulesRegistry.toBrowserJsConfigJson())
            .replace("__DOWNLOADED_KEYS__", "[]")
            .replace("__IN_PROGRESS_KEYS__", "[]")
            .replace("__DOWNLOADED_LABEL__", JSONObject.quote(""))
            .replace("__IN_PROGRESS_LABEL__", JSONObject.quote(""))
    }

    fun buildHighlightDownloadedScript(
        context: Context,
        downloadedKeys: Collection<String>,
        inProgressKeys: Collection<String>,
        downloadedLabel: String,
        inProgressLabel: String
    ): String {
        val template = loadAssetCached(context, CHANNEL_SCANNER_ASSET)
            ?: return EMPTY_CHANNEL_HIGHLIGHT_JS
        return template
            .replace("__MODE__", JSONObject.quote("highlight"))
            .replace("__CFG_JSON__", WebRulesRegistry.toBrowserJsConfigJson())
            .replace("__DOWNLOADED_KEYS__", JSONArray(downloadedKeys.toList()).toString())
            .replace("__IN_PROGRESS_KEYS__", JSONArray(inProgressKeys.toList()).toString())
            .replace("__DOWNLOADED_LABEL__", JSONObject.quote(downloadedLabel))
            .replace("__IN_PROGRESS_LABEL__", JSONObject.quote(inProgressLabel))
    }

    private fun loadAssetCached(context: Context, assetName: String): String? {
        val existing = when (assetName) {
            MEDIA_INSPECTOR_ASSET -> cachedMediaTemplate
            ICON_INSPECTOR_ASSET -> cachedIconScript
            CHANNEL_SCANNER_ASSET -> cachedChannelScannerTemplate
            else -> null
        }
        if (!existing.isNullOrBlank()) return existing
        val loaded = runCatching {
            context.applicationContext.assets.open(assetName)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
        if (!loaded.isNullOrBlank()) {
            when (assetName) {
                MEDIA_INSPECTOR_ASSET -> cachedMediaTemplate = loaded
                ICON_INSPECTOR_ASSET -> cachedIconScript = loaded
                CHANNEL_SCANNER_ASSET -> cachedChannelScannerTemplate = loaded
            }
        }
        return loaded
    }

    private const val EMPTY_MEDIA_RESULT_JS =
        """(function(){return JSON.stringify({imageUrl:'',imageW:0,imageH:0,imageTitle:'',videos:[],hasVideoEl:false,postUrl:'',pageTitle:'',pageThumb:'',postImages:[],postVideos:[]});})();"""

    private const val EMPTY_ICON_RESULT_JS =
        """(function(){return JSON.stringify({icons:[],hasVideo:document.querySelectorAll('video').length>0});})();"""

    private const val EMPTY_CHANNEL_SCAN_JS =
        """(function(){return JSON.stringify({pageTitle:'',pageAuthor:'',items:[]});})();"""

    private const val EMPTY_CHANNEL_HIGHLIGHT_JS =
        """(function(){return JSON.stringify({highlightedDownloaded:0,highlightedInProgress:0});})();"""
}

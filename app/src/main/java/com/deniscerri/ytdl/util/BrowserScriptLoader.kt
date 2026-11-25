package com.deniscerri.ytdl.util

import android.content.Context
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import java.util.Locale

object BrowserScriptLoader {

    private const val MEDIA_INSPECTOR_ASSET = "browser_media_inspector.js"
    private const val ICON_INSPECTOR_ASSET = "browser_icon_inspector.js"

    @Volatile
    private var cachedMediaTemplate: String? = null

    @Volatile
    private var cachedIconScript: String? = null

    fun buildInspectMediaScript(context: Context, normX: Float, normY: Float): String {
        val template = loadAssetCached(context, MEDIA_INSPECTOR_ASSET, isMedia = true)
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
        return loadAssetCached(context, ICON_INSPECTOR_ASSET, isMedia = false)
            ?: EMPTY_ICON_RESULT_JS
    }

    private fun loadAssetCached(context: Context, assetName: String, isMedia: Boolean): String? {
        val existing = if (isMedia) cachedMediaTemplate else cachedIconScript
        if (!existing.isNullOrBlank()) return existing
        val loaded = runCatching {
            context.applicationContext.assets.open(assetName)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
        if (!loaded.isNullOrBlank()) {
            if (isMedia) cachedMediaTemplate = loaded else cachedIconScript = loaded
        }
        return loaded
    }

    private const val EMPTY_MEDIA_RESULT_JS =
        """(function(){return JSON.stringify({imageUrl:'',imageW:0,imageH:0,imageTitle:'',videos:[],hasVideoEl:false,postUrl:'',pageTitle:'',pageThumb:'',postImages:[],postVideos:[]});})();"""

    private const val EMPTY_ICON_RESULT_JS =
        """(function(){return JSON.stringify({icons:[],hasVideo:document.querySelectorAll('video').length>0});})();"""
}

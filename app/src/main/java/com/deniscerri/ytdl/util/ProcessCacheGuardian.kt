package com.deniscerri.ytdl.util

import android.content.Context
import com.deniscerri.ytdl.util.Extensions.getIDFromYoutubeURL
import com.deniscerri.ytdl.util.Extensions.isYoutubeURL
import java.io.File
import java.util.zip.CRC32

object ProcessCacheGuardian {

    fun purgeDownloadCache(
        context: Context,
        downloadId: Long,
        url: String? = null,
        keepCache: Boolean = false
    ) {
        if (!keepCache) {
            runCatching {
                val dlDir = File(FileUtil.getCacheDownloadsPath(context), downloadId.toString())
                if (dlDir.exists()) {
                    dlDir.deleteRecursively()
                }
            }
        }

        if (!url.isNullOrBlank()) {
            purgeInfoJsonForUrl(context, url)
        }
    }

    fun purgeInfoJsonForUrl(context: Context, url: String) {
        runCatching {
            val cachePath = FileUtil.getInfoJsonPath(context)
            val dir = File(cachePath)
            if (!dir.exists()) return

            val key = if (url.isYoutubeURL()) {
                url.getIDFromYoutubeURL() ?: url
            } else {
                url
            }
            val hashPrefix = crc32Hex(key)
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith(hashPrefix) && file.name.endsWith(".info.json")) {
                    file.delete()
                }
            }
        }
    }

    fun sweepOrphanedCaches(
        context: Context,
        activeOrPausedDownloadIds: Set<Long> = emptySet(),
        maxTempAgeMs: Long = 2 * 60 * 60 * 1000L,
        maxInfoJsonAgeMs: Long = 12 * 60 * 60 * 1000L
    ) {
        val now = System.currentTimeMillis()
        val tempCutoff = now - maxTempAgeMs
        val infoCutoff = now - maxInfoJsonAgeMs

        // 1. Clean orphaned download cache folders and stray config .txt files
        runCatching {
            val dlRoot = File(FileUtil.getCacheDownloadsPath(context))
            if (dlRoot.exists()) {
                dlRoot.listFiles()?.forEach { entry ->
                    if (entry.isDirectory) {
                        val id = entry.name.toLongOrNull()
                        val isProtected = id != null && id in activeOrPausedDownloadIds
                        if (!isProtected && entry.lastModified() < tempCutoff) {
                            entry.deleteRecursively()
                        }
                    } else if (entry.isFile && entry.name.endsWith(".txt") && entry.lastModified() < tempCutoff) {
                        entry.delete()
                    }
                }
            }
        }

        // 2. Clean expired .info.json files
        runCatching {
            val infoDir = File(FileUtil.getInfoJsonPath(context))
            if (infoDir.exists()) {
                infoDir.listFiles()?.forEach { entry ->
                    if (entry.isFile && entry.lastModified() < infoCutoff) {
                        entry.delete()
                    }
                }
            }
        }

        // 3. Clean stale tmp folder used during data-fetching (never touches domain_cookies or site_icons)
        runCatching {
            val tmpDir = File(FileUtil.getCachePath(context), "tmp")
            if (tmpDir.exists()) {
                tmpDir.listFiles()?.forEach { entry ->
                    if (entry.lastModified() < tempCutoff) {
                        if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
                    }
                }
            }
        }
    }

    private fun crc32Hex(text: String): String {
        val crc = CRC32()
        crc.update(text.toByteArray())
        return crc.value.toString(16)
    }
}

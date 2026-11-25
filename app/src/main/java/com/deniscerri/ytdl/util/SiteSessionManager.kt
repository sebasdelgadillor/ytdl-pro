package com.deniscerri.ytdl.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.CookieManager
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.models.SiteSession
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

object SiteSessionManager {

    data class ExtractedCookie(
        val domain: String,
        val path: String,
        val secure: Boolean,
        val expiryUnixSeconds: Long,
        val name: String,
        val value: String,
        val httpOnly: Boolean
    )

    private data class CookieMeta(
        val domain: String,
        val path: String,
        val secure: Boolean,
        val expiryUnixSeconds: Long,
        val httpOnly: Boolean
    )

    data class DomainSessionContext(
        val rootDomain: String,
        val cookieFile: File,
        val userAgent: String,
        val desktopMode: Boolean,
        val cookieCount: Int
    )

    private const val FALLBACK_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val iconHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getCookieHeaderForUrl(url: String): String? {
        return runCatching {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun extractLiveCookiesForDomain(context: Context, rootDomain: String): List<ExtractedCookie> {
        runCatching { CookieManager.getInstance().flush() }

        val relatedDomains = DomainUtil.relatedCookieDomains(rootDomain)
        val cookieMap = LinkedHashMap<String, ExtractedCookie>()
        val metaByName = HashMap<String, CookieMeta>()

        // Source A: Chromium WebView SQLite Cookies database (read from an isolated snapshot copy)
        runCatching {
            val dbPath = File(context.applicationInfo.dataDir).walkTopDown()
                .find { it.name == "Cookies" && !it.path.contains("incognito_store") }
            if (dbPath != null && dbPath.exists()) {
                val snapDir = File(context.cacheDir, "cookie_snap_${System.nanoTime()}").apply { mkdirs() }
                try {
                    val snapDb = File(snapDir, "Cookies")
                    dbPath.copyTo(snapDb, overwrite = true)
                    listOf("-wal", "-shm", "-journal").forEach { suffix ->
                        val sibling = File(dbPath.parentFile, "${dbPath.name}$suffix")
                        if (sibling.exists() && sibling.length() > 0L) {
                            runCatching { sibling.copyTo(File(snapDir, "${snapDb.name}$suffix"), overwrite = true) }
                        }
                    }

                    val db = SQLiteDatabase.openDatabase(snapDb.absolutePath, null, OPEN_READONLY)
                    db.use { sqlite ->
                        val hasHttpOnlyCol = runCatching {
                            sqlite.rawQuery("PRAGMA table_info(cookies)", null).use { c ->
                                var found = false
                                val nameIdx = c.getColumnIndex("name")
                                while (c.moveToNext()) {
                                    if (nameIdx >= 0 && c.getString(nameIdx) == "is_httponly") {
                                        found = true
                                        break
                                    }
                                }
                                found
                            }
                        }.getOrDefault(false)

                        val columns = if (hasHttpOnlyCol) {
                            arrayOf("host_key", "path", "is_secure", "expires_utc", "name", "value", "is_httponly")
                        } else {
                            arrayOf("host_key", "path", "is_secure", "expires_utc", "name", "value")
                        }

                        val clauses = mutableListOf<String>()
                        val args = mutableListOf<String>()
                        for (d in relatedDomains) {
                            clauses.add("host_key = ? OR host_key LIKE ?")
                            args.add(d)
                            args.add("%.$d")
                        }
                        val selection = clauses.joinToString(" OR ")

                        sqlite.query("cookies", columns, selection, args.toTypedArray(), null, null, null).use { cursor ->
                            val hostIdx = cursor.getColumnIndexOrThrow("host_key")
                            val pathIdx = cursor.getColumnIndexOrThrow("path")
                            val secureIdx = cursor.getColumnIndexOrThrow("is_secure")
                            val expiryIdx = cursor.getColumnIndexOrThrow("expires_utc")
                            val nameIdx = cursor.getColumnIndexOrThrow("name")
                            val valueIdx = cursor.getColumnIndexOrThrow("value")
                            val httpOnlyIdx = if (hasHttpOnlyCol) cursor.getColumnIndex("is_httponly") else -1
                            val nowSec = System.currentTimeMillis() / 1000L

                            while (cursor.moveToNext()) {
                                val name = cursor.getString(nameIdx)?.trim().orEmpty()
                                if (name.isEmpty()) continue

                                val rawHost = cursor.getString(hostIdx)?.trim().orEmpty()
                                val normalizedDomain = normalizeCookieDomain(rawHost, relatedDomains.first())
                                val path = cursor.getString(pathIdx)?.ifBlank { "/" } ?: "/"
                                val secure = cursor.getLong(secureIdx) == 1L
                                val expiry = DomainUtil.chromiumExpiryToUnixSeconds(cursor.getLong(expiryIdx))
                                if (expiry > 0L && expiry < nowSec) continue
                                val httpOnly = httpOnlyIdx >= 0 && cursor.getLong(httpOnlyIdx) == 1L
                                val value = cursor.getString(valueIdx).orEmpty()

                                val meta = CookieMeta(normalizedDomain, path, secure, expiry, httpOnly)
                                metaByName.putIfAbsent(name.lowercase(Locale.US), meta)

                                if (value.isNotEmpty()) {
                                    val key = dedupKey(normalizedDomain, path, name)
                                    cookieMap[key] = ExtractedCookie(
                                        domain = normalizedDomain,
                                        path = path,
                                        secure = secure,
                                        expiryUnixSeconds = expiry,
                                        name = name,
                                        value = value,
                                        httpOnly = httpOnly
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    runCatching { snapDir.deleteRecursively() }
                }
            }
        }

        // Source B: Android CookieManager (always holds live decrypted values on Android 12-16+)
        runCatching {
            val cm = CookieManager.getInstance()
            for (d in relatedDomains) {
                val probeUrls = listOf("https://$d/", "https://www.$d/", "https://m.$d/")
                for (probeUrl in probeUrls) {
                    val header = cm.getCookie(probeUrl) ?: continue
                    header.split(";").forEach { part ->
                        val eqIndex = part.indexOf('=')
                        if (eqIndex <= 0) return@forEach
                        val name = part.substring(0, eqIndex).trim()
                        val value = part.substring(eqIndex + 1).trim()
                        if (name.isEmpty() || value.isEmpty()) return@forEach

                        val meta = metaByName[name.lowercase(Locale.US)]
                        val domain = meta?.domain ?: ".$d"
                        val path = meta?.path ?: "/"
                        val secure = meta?.secure ?: true
                        val expiry = meta?.expiryUnixSeconds ?: 0L
                        val httpOnly = meta?.httpOnly ?: false

                        val key = dedupKey(domain, path, name)
                        val existing = cookieMap[key]
                        if (existing == null || existing.value.isEmpty()) {
                            cookieMap[key] = ExtractedCookie(
                                domain = domain,
                                path = path,
                                secure = secure,
                                expiryUnixSeconds = expiry,
                                name = name,
                                value = value,
                                httpOnly = httpOnly
                            )
                        }
                    }
                }
            }
        }

        return cookieMap.values.toList()
    }

    fun buildNetscapeCookieText(cookies: List<ExtractedCookie>, rootDomain: String): String {
        return buildString {
            append("# Netscape HTTP Cookie File\n")
            append("# Live session cookies for ").append(rootDomain).append('\n')
            cookies.forEach { c ->
                val includeSubdomains = c.domain.startsWith(".")
                append(
                    DomainUtil.formatNetscapeCookieLine(
                        domain = c.domain,
                        includeSubdomains = includeSubdomains,
                        path = c.path,
                        secure = c.secure,
                        expiryUnixSeconds = c.expiryUnixSeconds,
                        name = c.name,
                        value = c.value,
                        httpOnly = c.httpOnly
                    )
                )
                append('\n')
            }
        }
    }

    fun prepareSessionContextForUrl(context: Context, url: String): DomainSessionContext? {
        val rootDomain = DomainUtil.normalizeRootDomain(url) ?: return null
        val dao = DBManager.getInstance(context).siteSessionDao
        val existingSession = runCatching { dao.getByDomain(rootDomain) }.getOrNull()
        val cookies = extractLiveCookiesForDomain(context, rootDomain)

        val shouldInject = cookies.isNotEmpty() && (
            existingSession?.isLoggedIn == true ||
                cookies.any { DomainUtil.isLikelyAuthCookie(it.name, rootDomain) }
            )
        if (!shouldInject) return null

        val outFile = writeDomainCookieFile(context, rootDomain, cookies)

        if (existingSession != null) {
            runCatching {
                dao.updateUsageSync(rootDomain, cookies.size, System.currentTimeMillis())
            }
        }

        val ua = existingSession?.userAgent?.takeIf { it.isNotBlank() } ?: FALLBACK_MOBILE_UA
        return DomainSessionContext(
            rootDomain = rootDomain,
            cookieFile = outFile,
            userAgent = ua,
            desktopMode = existingSession?.desktopMode == true,
            cookieCount = cookies.size
        )
    }

    fun prepareCookieFileForUrl(context: Context, url: String): File? {
        return prepareSessionContextForUrl(context, url)?.cookieFile
    }

    fun getPreferredUserAgentForUrl(context: Context, url: String): String? {
        val rootDomain = DomainUtil.normalizeRootDomain(url) ?: return null
        return runCatching {
            DBManager.getInstance(context).siteSessionDao.getByDomain(rootDomain)
                ?.userAgent?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun syncSessionFromBrowser(
        context: Context,
        pageUrl: String,
        pageTitle: String?,
        candidateIconUrls: List<String>,
        receivedIconBitmap: Bitmap?,
        desktopMode: Boolean,
        userAgent: String,
        forceSave: Boolean = false
    ): SiteSession? {
        val rootDomain = DomainUtil.normalizeRootDomain(pageUrl) ?: return null
        val cookies = extractLiveCookiesForDomain(context, rootDomain)
        val hasAuth = cookies.any { DomainUtil.isLikelyAuthCookie(it.name, rootDomain) }

        val dao = DBManager.getInstance(context).siteSessionDao
        val existing = runCatching { dao.getByDomain(rootDomain) }.getOrNull()

        if (!forceSave && !hasAuth && existing == null) {
            return null
        }

        val orderedIconCandidates = buildOrderedIconCandidates(pageUrl, rootDomain, candidateIconUrls)
        val firstCandidateUrl = orderedIconCandidates.firstOrNull().orEmpty()
        val existingIconFile = existing?.localIconPath?.takeIf { it.isNotBlank() }?.let { File(it) }
        val needsIconFetch = forceSave ||
            existingIconFile == null ||
            !existingIconFile.exists() ||
            existingIconFile.length() == 0L ||
            (firstCandidateUrl.isNotEmpty() && firstCandidateUrl != existing?.faviconUrl)

        var resolvedIconUrl = existing?.faviconUrl.orEmpty()
        var localIconPath = existing?.localIconPath.orEmpty()

        if (needsIconFetch) {
            val (downloadedBitmap, usedUrl) = resolveBestIconBitmap(
                pageUrl = pageUrl,
                candidateUrls = orderedIconCandidates,
                receivedIconBitmap = receivedIconBitmap,
                userAgent = userAgent
            )
            if (usedUrl.isNotBlank()) {
                resolvedIconUrl = usedUrl
            }
            if (downloadedBitmap != null) {
                val savedPath = saveIconBitmap(context, rootDomain, downloadedBitmap)
                if (savedPath != null) {
                    localIconPath = savedPath
                }
            }
        }

        val session = SiteSession(
            id = existing?.id ?: 0L,
            domain = rootDomain,
            displayName = existing?.displayName?.takeIf { it.isNotBlank() }
                ?: DomainUtil.displayNameForDomain(rootDomain, pageTitle),
            url = pageUrl.takeIf { it.startsWith("http") } ?: DomainUtil.canonicalUrlForDomain(rootDomain),
            faviconUrl = resolvedIconUrl,
            localIconPath = localIconPath,
            cookieCount = cookies.size,
            isLoggedIn = true,
            desktopMode = desktopMode,
            userAgent = userAgent,
            lastUsedTimestamp = System.currentTimeMillis()
        )

        val rowId = dao.insert(session)
        session.id = if (existing != null && existing.id > 0L) existing.id else rowId

        if (cookies.isNotEmpty()) {
            writeDomainCookieFile(context, rootDomain, cookies)
        }
        if (existing == null || existing.cookieCount != cookies.size || forceSave) {
            clearCachedInfoJsonForUrl(context, pageUrl)
        }

        return session
    }

    suspend fun refreshSession(context: Context, domain: String): SiteSession? {
        val rootDomain = DomainUtil.normalizeRootDomain(domain) ?: domain
        val dao = DBManager.getInstance(context).siteSessionDao
        val existing = dao.getByDomain(rootDomain) ?: return null

        val cookies = extractLiveCookiesForDomain(context, rootDomain)
        val iconFile = existing.localIconPath.takeIf { it.isNotBlank() }?.let { File(it) }
        var localIconPath = existing.localIconPath
        var faviconUrl = existing.faviconUrl

        if (iconFile == null || !iconFile.exists() || iconFile.length() == 0L) {
            val candidates = buildOrderedIconCandidates(
                existing.url,
                rootDomain,
                listOfNotNull(existing.faviconUrl.takeIf { it.isNotBlank() })
            )
            val (bmp, usedUrl) = resolveBestIconBitmap(
                pageUrl = existing.url,
                candidateUrls = candidates,
                receivedIconBitmap = null,
                userAgent = existing.userAgent
            )
            if (bmp != null) {
                saveIconBitmap(context, rootDomain, bmp)?.let { localIconPath = it }
            }
            if (usedUrl.isNotBlank()) faviconUrl = usedUrl
        }

        existing.cookieCount = cookies.size
        existing.localIconPath = localIconPath
        existing.faviconUrl = faviconUrl
        existing.lastUsedTimestamp = System.currentTimeMillis()
        dao.update(existing)

        if (cookies.isNotEmpty()) {
            writeDomainCookieFile(context, rootDomain, cookies)
        }
        clearCachedInfoJsonForUrl(context, existing.url)

        return existing
    }

    suspend fun removeSession(
        context: Context,
        domain: String,
        clearBrowserCookies: Boolean = true
    ) {
        val rootDomain = DomainUtil.normalizeRootDomain(domain) ?: domain
        val dao = DBManager.getInstance(context).siteSessionDao
        dao.deleteByDomain(rootDomain)

        runCatching { getDomainCookieFile(context, rootDomain).delete() }
        runCatching { getSiteIconFile(context, rootDomain).delete() }

        if (clearBrowserCookies) {
            runCatching {
                val cm = CookieManager.getInstance()
                val related = DomainUtil.relatedCookieDomains(rootDomain)
                val cookies = extractLiveCookiesForDomain(context, rootDomain)
                val names = cookies.map { it.name }.distinct()
                for (d in related) {
                    val origins = listOf("https://$d", "https://www.$d", "https://m.$d")
                    for (name in names) {
                        for (origin in origins) {
                            cm.setCookie(origin, "$name=; Max-Age=0; path=/")
                            cm.setCookie(origin, "$name=; Max-Age=0; path=/; domain=.$d")
                        }
                    }
                }
                cm.flush()
            }
        }
    }

    suspend fun importNetscapeCookies(
        context: Context,
        rawCookieText: String,
        fallbackDomain: String? = null,
        userAgent: String = FALLBACK_MOBILE_UA
    ): List<SiteSession> {
        val parsed = BrowserMediaUtil.parseNetscapeCookieText(rawCookieText)
        if (parsed.isEmpty()) return emptyList()

        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        val grouped = linkedMapOf<String, MutableList<ExtractedCookie>>()

        parsed.forEach { entry ->
            val rootDomain = DomainUtil.normalizeRootDomain(entry.domain)
                ?: fallbackDomain?.let { DomainUtil.normalizeRootDomain(it) }
                ?: return@forEach

            val cleanHost = entry.domain.trimStart('.')
            val cookieDomain = normalizeCookieDomain(entry.domain, rootDomain)

            if (cm != null && cleanHost.isNotEmpty()) {
                val cookieAttr = buildString {
                    append(entry.name).append('=').append(entry.value)
                    append("; Path=").append(entry.path)
                    if (entry.secure) append("; Secure")
                    if (entry.httpOnly) append("; HttpOnly")
                    if (entry.includeSubdomains) append("; Domain=.").append(cleanHost)
                }
                runCatching {
                    cm.setCookie("https://$cleanHost/", cookieAttr)
                    if (entry.includeSubdomains && !cleanHost.startsWith("www.")) {
                        cm.setCookie("https://www.$cleanHost/", cookieAttr)
                    }
                }
            }

            grouped.getOrPut(rootDomain) { mutableListOf() }.add(
                ExtractedCookie(
                    domain = cookieDomain,
                    path = entry.path,
                    secure = entry.secure,
                    expiryUnixSeconds = entry.expiryUnixSeconds,
                    name = entry.name,
                    value = entry.value,
                    httpOnly = entry.httpOnly
                )
            )
        }

        runCatching { cm?.flush() }
        if (grouped.isEmpty()) return emptyList()

        val dao = DBManager.getInstance(context).siteSessionDao
        val importedSessions = mutableListOf<SiteSession>()

        grouped.forEach { (rootDomain, cookies) ->
            val merged = (extractLiveCookiesForDomain(context, rootDomain) + cookies)
                .associateBy { dedupKey(it.domain, it.path, it.name) }
                .values
                .toList()

            writeDomainCookieFile(context, rootDomain, merged)

            val existing = runCatching { dao.getByDomain(rootDomain) }.getOrNull()
            val canonicalUrl = DomainUtil.canonicalUrlForDomain(rootDomain)
            var resolvedIconUrl = existing?.faviconUrl.orEmpty()
            var localIconPath = existing?.localIconPath.orEmpty()
            val existingIconFile = localIconPath.takeIf { it.isNotBlank() }?.let { File(it) }

            if (existingIconFile == null || !existingIconFile.exists() || existingIconFile.length() == 0L) {
                val candidates = buildOrderedIconCandidates(canonicalUrl, rootDomain, emptyList())
                val (bmp, usedUrl) = resolveBestIconBitmap(
                    pageUrl = canonicalUrl,
                    candidateUrls = candidates,
                    receivedIconBitmap = null,
                    userAgent = existing?.userAgent?.ifBlank { userAgent } ?: userAgent
                )
                if (usedUrl.isNotBlank()) resolvedIconUrl = usedUrl
                if (bmp != null) saveIconBitmap(context, rootDomain, bmp)?.let { localIconPath = it }
            }

            val session = SiteSession(
                id = existing?.id ?: 0L,
                domain = rootDomain,
                displayName = existing?.displayName?.takeIf { it.isNotBlank() }
                    ?: DomainUtil.displayNameForDomain(rootDomain, null),
                url = existing?.url?.takeIf { it.startsWith("http") } ?: canonicalUrl,
                faviconUrl = resolvedIconUrl,
                localIconPath = localIconPath,
                cookieCount = merged.size,
                isLoggedIn = true,
                desktopMode = existing?.desktopMode == true,
                userAgent = existing?.userAgent?.takeIf { it.isNotBlank() } ?: userAgent,
                lastUsedTimestamp = System.currentTimeMillis()
            )
            val rowId = dao.insert(session)
            session.id = if (existing != null && existing.id > 0L) existing.id else rowId
            clearCachedInfoJsonForUrl(context, session.url)
            importedSessions.add(session)
        }

        return importedSessions
    }

    fun getDomainCookieFile(context: Context, rootDomain: String): File {
        val dir = File(context.cacheDir, "domain_cookies").apply { mkdirs() }
        return File(dir, "$rootDomain.txt")
    }

    private fun writeDomainCookieFile(
        context: Context,
        rootDomain: String,
        cookies: List<ExtractedCookie>
    ): File {
        val target = getDomainCookieFile(context, rootDomain)
        val newContent = buildNetscapeCookieText(cookies, rootDomain)
        if (target.exists() && runCatching { target.readText() }.getOrNull() == newContent) {
            return target
        }
        val tmp = File(target.parentFile, "$rootDomain.txt.tmp")
        tmp.writeText(newContent)
        if (!tmp.renameTo(target)) {
            target.writeText(newContent)
            tmp.delete()
        }
        runCatching {
            target.setReadable(false, false)
            target.setReadable(true, true)
        }
        return target
    }

    private fun getSiteIconFile(context: Context, rootDomain: String): File {
        val dir = File(context.filesDir, "site_icons").apply { mkdirs() }
        return File(dir, "$rootDomain.png")
    }

    private fun saveIconBitmap(context: Context, rootDomain: String, bitmap: Bitmap): String? {
        return runCatching {
            val file = getSiteIconFile(context, rootDomain)
            val tmp = File(file.parentFile, "$rootDomain.png.tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            file.absolutePath
        }.getOrNull()
    }

    private fun buildOrderedIconCandidates(
        pageUrl: String,
        rootDomain: String,
        rawCandidates: List<String>
    ): List<String> {
        val host = runCatching { Uri.parse(pageUrl).host }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "www.$rootDomain"
        val result = linkedSetOf<String>()
        rawCandidates.map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .filterNot { it.lowercase(Locale.US).substringBefore('?').endsWith(".svg") }
            .forEach { result.add(it) }

        result.add("https://$host/apple-touch-icon.png")
        result.add("https://$host/favicon.ico")
        if (host != rootDomain) {
            result.add("https://$rootDomain/favicon.ico")
        }
        return result.toList()
    }

    private fun resolveBestIconBitmap(
        pageUrl: String,
        candidateUrls: List<String>,
        receivedIconBitmap: Bitmap?,
        userAgent: String
    ): Pair<Bitmap?, String> {
        var bestBitmap: Bitmap? = null
        var bestScore = -1
        var bestUrl = ""

        for (candidate in candidateUrls.take(6)) {
            val bmp = downloadBitmap(candidate, pageUrl, userAgent) ?: continue
            val size = minOf(bmp.width, bmp.height)
            val score = when {
                size in 64..256 -> 1000 + size
                size > 256 -> 800
                size >= 32 -> 500 + size
                size >= 16 -> 200 + size
                else -> 50
            }
            if (score > bestScore) {
                bestScore = score
                bestBitmap = bmp
                bestUrl = candidate
            }
            if (size in 64..256) break
        }

        if (bestBitmap == null && receivedIconBitmap != null && receivedIconBitmap.width >= 16) {
            bestBitmap = receivedIconBitmap
        }
        return Pair(bestBitmap, bestUrl)
    }

    private fun downloadBitmap(iconUrl: String, refererUrl: String, userAgent: String): Bitmap? {
        return runCatching {
            val reqBuilder = Request.Builder().url(iconUrl)
            if (userAgent.isNotBlank()) {
                reqBuilder.header("User-Agent", userAgent)
            }
            if (refererUrl.startsWith("http")) {
                reqBuilder.header("Referer", refererUrl)
            }
            getCookieHeaderForUrl(iconUrl)?.let { cookie ->
                reqBuilder.header("Cookie", cookie)
            }
            iconHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.US)
                if (contentType.contains("svg")) return@use null
                val bytes = resp.body.bytes()
                if (bytes.isEmpty()) return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    private fun normalizeCookieDomain(rawHost: String, fallbackDomain: String): String {
        val clean = rawHost.trim()
        if (clean.isEmpty()) return ".$fallbackDomain"
        return if (clean.startsWith(".")) clean else ".$clean"
    }

    private fun dedupKey(domain: String, path: String, name: String): String {
        return "${domain.lowercase(Locale.US)}|$path|${name.lowercase(Locale.US)}"
    }

    fun getDomainCookieFileIfPresent(context: Context, url: String): File? {
        val rootDomain = DomainUtil.normalizeRootDomain(url) ?: return null
        val file = getDomainCookieFile(context, rootDomain)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    private fun clearCachedInfoJsonForUrl(context: Context, url: String) {
        runCatching {
            val cacheDir = File(FileUtil.getInfoJsonPath(context))
            if (!cacheDir.exists()) return
            val crc = java.util.zip.CRC32().apply { update(url.toByteArray()) }.value.toString(16)
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(crc) || file.name.endsWith(".info.json")) {
                    // Only clear matching hash or stale cache when cookie state changes
                    if (file.name.startsWith(crc)) {
                        file.delete()
                    }
                }
            }
        }
    }
}

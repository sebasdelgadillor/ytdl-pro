package com.deniscerri.ytdl.util

import com.deniscerri.ytdl.util.rules.DomainRules
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import java.net.URI
import java.util.Locale

object DomainUtil {

    private fun rules(): DomainRules = WebRulesRegistry.current().domains

    fun normalizeRootDomain(urlOrHost: String): String? {
        val trimmed = urlOrHost.trim()
        if (trimmed.isEmpty()) return null

        val rawHost = extractHost(trimmed) ?: return null
        val host = rawHost.lowercase(Locale.US).trimEnd('.')
        if (host.isEmpty() || host.contains("..") || !host.contains('.')) return null
        if (host == "localhost" || isIpAddress(host)) return null
        if (!host.matches(Regex("^[a-z0-9.-]+$"))) return null

        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null

        val domRules = rules()
        val lastTwo = "${labels[labels.size - 2]}.${labels.last()}"
        val isMultiPart = domRules.multiPartPublicSuffixes.any { it.equals(lastTwo, ignoreCase = true) }
        val registrable = if (labels.size >= 3 && isMultiPart) {
            "${labels[labels.size - 3]}.$lastTwo"
        } else {
            lastTwo
        }

        return domRules.aliases[registrable]?.lowercase(Locale.US) ?: registrable
    }

    private fun extractHost(input: String): String? {
        if (input.contains("://")) {
            return runCatching {
                val uri = URI(input)
                uri.host?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: fallbackHostParse(input)
        }
        return runCatching {
            val uri = URI("https://$input")
            uri.host?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallbackHostParse(input)
    }

    private fun fallbackHostParse(input: String): String? {
        val withoutScheme = input.substringAfter("://")
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPart = authority.substringAfterLast('@').substringBefore(':')
        return hostPart.takeIf { it.isNotBlank() }
    }

    private fun isIpAddress(host: String): Boolean {
        return host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
    }

    fun relatedCookieDomains(rootDomain: String): List<String> {
        val normalized = normalizeRootDomain(rootDomain) ?: rootDomain.lowercase(Locale.US)
        return rules().relatedCookieDomains[normalized]?.takeIf { it.isNotEmpty() }
            ?: listOf(normalized)
    }

    fun canonicalUrlForDomain(rootDomain: String): String {
        val normalized = normalizeRootDomain(rootDomain) ?: rootDomain.lowercase(Locale.US)
        return rules().canonicalUrls[normalized]?.takeIf { it.isNotBlank() }
            ?: "https://www.$normalized/"
    }

    fun displayNameForDomain(rootDomain: String, pageTitle: String? = null): String {
        val normalized = normalizeRootDomain(rootDomain) ?: rootDomain.lowercase(Locale.US)
        rules().displayNames[normalized]?.takeIf { it.isNotBlank() }?.let { return it }
        val primaryLabel = normalized.substringBefore('.')
        if (primaryLabel.isNotBlank()) {
            return primaryLabel.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            }
        }
        return pageTitle?.trim()?.takeIf { it.isNotEmpty() } ?: normalized
    }

    fun chromiumExpiryToUnixSeconds(rawExpiry: Long): Long {
        if (rawExpiry <= 0L) return 0L
        return if (rawExpiry > 10_000_000_000_000L) {
            ((rawExpiry / 1_000_000L) - 11_644_473_600L).coerceAtLeast(0L)
        } else {
            rawExpiry
        }
    }

    fun formatNetscapeCookieLine(
        domain: String,
        includeSubdomains: Boolean,
        path: String,
        secure: Boolean,
        expiryUnixSeconds: Long,
        name: String,
        value: String,
        httpOnly: Boolean = false
    ): String {
        val cleanDomain = domain.trim()
        val domainWithDot = if (includeSubdomains && !cleanDomain.startsWith(".")) {
            ".$cleanDomain"
        } else {
            cleanDomain
        }
        val finalDomain = if (httpOnly) "#HttpOnly_$domainWithDot" else domainWithDot
        val subdomainFlag = includeSubdomains.toString().uppercase(Locale.US)
        val finalPath = path.ifBlank { "/" }
        val secureFlag = secure.toString().uppercase(Locale.US)
        val finalExpiry = expiryUnixSeconds.coerceAtLeast(0L).toString()

        return listOf(
            finalDomain,
            subdomainFlag,
            finalPath,
            secureFlag,
            finalExpiry,
            name,
            value
        ).joinToString("\t")
    }

    fun isLikelyAuthCookie(name: String, domain: String = ""): Boolean {
        val lower = name.trim().lowercase(Locale.US)
        if (lower.isEmpty()) return false
        val domRules = rules()
        if (domRules.authCookieExact.any { it.equals(lower, ignoreCase = true) }) return true
        return domRules.authCookieSubstrings.any { lower.contains(it.lowercase(Locale.US)) }
    }
}

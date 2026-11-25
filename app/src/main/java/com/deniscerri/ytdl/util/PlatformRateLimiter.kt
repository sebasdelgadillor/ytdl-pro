package com.deniscerri.ytdl.util

import com.deniscerri.ytdl.util.rules.RateLimitPolicyRule
import com.deniscerri.ytdl.util.rules.RateLimitRules
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

object PlatformRateLimiter {

    data class DomainPolicy(
        val minIntervalMs: Long,
        val maxIntervalMs: Long,
        val ytdlpSleepRequestsSec: String,
        val maxRetries: String,
        val fragmentRetries: String,
        val retrySleepHttp: String,
        val retrySleepFragment: String
    )

    private data class RateLimitState(
        val cooldownUntilMs: Long,
        val consecutiveHits: Int
    )

    private val lock = Any()
    private val lastRequestByDomain = ConcurrentHashMap<String, Long>()
    private val backoffByDomain = ConcurrentHashMap<String, RateLimitState>()

    private fun rules(): RateLimitRules = WebRulesRegistry.current().rateLimits

    private fun RateLimitPolicyRule.toDomainPolicy(): DomainPolicy = DomainPolicy(
        minIntervalMs = minIntervalMs.coerceAtLeast(0L),
        maxIntervalMs = maxOf(maxIntervalMs, minIntervalMs.coerceAtLeast(0L)),
        ytdlpSleepRequestsSec = ytdlpSleepRequestsSec,
        maxRetries = maxRetries,
        fragmentRetries = fragmentRetries,
        retrySleepHttp = retrySleepHttp,
        retrySleepFragment = retrySleepFragment
    )

    fun policyForDomain(rootDomain: String?): DomainPolicy {
        val clean = rootDomain?.trim()?.lowercase(Locale.US).orEmpty()
        val rl = rules()
        val selected = when {
            clean.isNotEmpty() && rl.strictDomains.any { it.equals(clean, ignoreCase = true) } ->
                rl.strictPolicy ?: DEFAULT_STRICT_POLICY
            clean.isNotEmpty() && rl.standardDomains.any { it.equals(clean, ignoreCase = true) } ->
                rl.standardPolicy ?: DEFAULT_STANDARD_POLICY
            else -> rl.defaultPolicy ?: DEFAULT_FALLBACK_POLICY
        }
        return selected.toDomainPolicy()
    }

    fun policyForUrl(url: String): DomainPolicy {
        return policyForDomain(DomainUtil.normalizeRootDomain(url))
    }

    fun computeWaitMillis(
        domain: String,
        nowMs: Long = System.currentTimeMillis(),
        jitterFraction: Double = ThreadLocalRandom.current().nextDouble()
    ): Long {
        val cleanDomain = DomainUtil.normalizeRootDomain(domain) ?: domain.trim().lowercase(Locale.US)
        if (cleanDomain.isEmpty()) return 0L

        val policy = policyForDomain(cleanDomain)
        val clampedJitter = jitterFraction.coerceIn(0.0, 1.0)
        val targetInterval = policy.minIntervalMs +
            ((policy.maxIntervalMs - policy.minIntervalMs) * clampedJitter).toLong()

        val backoffWait = backoffByDomain[cleanDomain]?.let { state ->
            (state.cooldownUntilMs - nowMs).coerceAtLeast(0L)
        } ?: 0L

        val lastTs = lastRequestByDomain[cleanDomain]
        val intervalWait = if (lastTs == null) {
            0L
        } else {
            ((lastTs + targetInterval) - nowMs).coerceAtLeast(0L)
        }

        return maxOf(backoffWait, intervalWait)
    }

    suspend fun awaitTurnForUrl(
        url: String,
        onWaiting: ((Long) -> Unit)? = null
    ): Long {
        val cleanDomain = DomainUtil.normalizeRootDomain(url) ?: return 0L
        val now = System.currentTimeMillis()
        val waitMs = synchronized(lock) {
            val w = computeWaitMillis(cleanDomain, nowMs = now)
            // Reserve slot at the target completion time so concurrent callers serialize cleanly
            lastRequestByDomain[cleanDomain] = now + w
            w
        }

        if (waitMs > 0L) {
            if (waitMs >= 1_000L) {
                onWaiting?.invoke(waitMs)
            }
            delay(waitMs)
        }
        return waitMs
    }

    fun isRateLimitError(message: String?): Boolean {
        val lower = message?.lowercase(Locale.US) ?: return false
        return rules().rateLimitErrorMarkers.any { marker ->
            marker.isNotBlank() && lower.contains(marker.lowercase(Locale.US))
        }
    }

    fun recordRateLimitForUrl(
        url: String,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val cleanDomain = DomainUtil.normalizeRootDomain(url) ?: return 0L
        return synchronized(lock) {
            val prev = backoffByDomain[cleanDomain]
            val hits = if (prev != null && prev.cooldownUntilMs > nowMs - 120_000L) {
                prev.consecutiveHits + 1
            } else {
                1
            }
            val backoffDurationMs = when (hits) {
                1 -> 15_000L
                2 -> 30_000L
                else -> 45_000L
            }
            backoffByDomain[cleanDomain] = RateLimitState(
                cooldownUntilMs = nowMs + backoffDurationMs,
                consecutiveHits = hits
            )
            backoffDurationMs
        }
    }

    fun resetForTests() {
        synchronized(lock) {
            lastRequestByDomain.clear()
            backoffByDomain.clear()
        }
    }

    private val DEFAULT_STRICT_POLICY = RateLimitPolicyRule(
        minIntervalMs = 2_500L,
        maxIntervalMs = 4_500L,
        ytdlpSleepRequestsSec = "1.5",
        maxRetries = "3",
        fragmentRetries = "3",
        retrySleepHttp = "http:exp=2:20",
        retrySleepFragment = "fragment:exp=1:10"
    )

    private val DEFAULT_STANDARD_POLICY = RateLimitPolicyRule(
        minIntervalMs = 1_000L,
        maxIntervalMs = 2_000L,
        ytdlpSleepRequestsSec = "1",
        maxRetries = "3",
        fragmentRetries = "3",
        retrySleepHttp = "http:exp=2:15",
        retrySleepFragment = "fragment:exp=1:10"
    )

    private val DEFAULT_FALLBACK_POLICY = RateLimitPolicyRule(
        minIntervalMs = 500L,
        maxIntervalMs = 1_200L,
        ytdlpSleepRequestsSec = "",
        maxRetries = "3",
        fragmentRetries = "3",
        retrySleepHttp = "http:exp=1:10",
        retrySleepFragment = "fragment:exp=1:8"
    )
}

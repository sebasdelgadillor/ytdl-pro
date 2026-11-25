package com.deniscerri.ytdl

import com.deniscerri.ytdl.util.BrowserMediaUtil
import com.deniscerri.ytdl.util.DomainUtil
import com.deniscerri.ytdl.util.PlatformRateLimiter
import com.deniscerri.ytdl.util.extractors.fallback.FallbackMediaRules
import com.deniscerri.ytdl.util.rules.WebRulesRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebRulesRegistryTest {

    @After
    fun tearDown() {
        WebRulesRegistry.resetToDefaults()
        PlatformRateLimiter.resetForTests()
    }

    @Test
    fun bundledWebRulesJson_parsesCleanlyAndMatchesExpectedSections() {
        val assetFile = listOf(
            File("src/main/assets/web_rules.json"),
            File("app/src/main/assets/web_rules.json")
        ).firstOrNull { it.exists() }
        assertNotNull("Expected web_rules.json asset file", assetFile)

        val raw = assetFile!!.readText(Charsets.UTF_8)
        val parsed = WebRulesRegistry.parseJson(raw)
        assertTrue("Bundled web_rules.json should parse: ${parsed.exceptionOrNull()}", parsed.isSuccess)

        val cfg = parsed.getOrThrow()
        assertEquals(1, cfg.schemaVersion)
        assertTrue(cfg.quickPlatforms.size >= 5)
        assertEquals("youtube.com", cfg.domains.aliases["youtu.be"])
        assertEquals(listOf("instagram.com", "meta.com"), cfg.domains.relatedCookieDomains["instagram.com"])
        assertTrue(cfg.rateLimits.strictDomains.contains("instagram.com"))
        assertTrue(cfg.mediaDetection.trustedImageCdnMarkers.contains("scontent"))
        assertTrue(cfg.fallbackScraper.htmlJsonVideoKeys.any { it.key == "playable_url_quality_hd" && it.minHeight == 720 })

        WebRulesRegistry.applyConfig(cfg, isOverride = false)
        val jsCfg = WebRulesRegistry.toBrowserJsConfigJson()
        assertTrue(jsCfg.contains("postContainerSelectors"))
        assertTrue(jsCfg.contains("highPriorityPermalinkMarkers"))
        assertTrue(jsCfg.contains("feedPathMarkers"))
        assertTrue(jsCfg.contains("genericPlaceholderTitles"))
        assertTrue(cfg.mediaDetection.permalinkNoiseMarkers.contains("/reels/audio/"))
        assertTrue(
            com.deniscerri.ytdl.util.rules.WebRulesConfig.defaults()
                .mediaDetection.permalinkNoiseMarkers.contains("/reels/audio/")
        )
    }

    @Test
    fun partialOverrideJson_mergesOverDefaultsWithoutWipingOtherSections() {
        val overrideJson = """
            {
              "rulesVersion": "2026.12.01-hotfix",
              "domains": {
                "aliases": {
                  "customshort.link": "instagram.com"
                },
                "displayNames": {
                  "examplemedia.org": "Example Media"
                }
              },
              "rateLimits": {
                "strictDomains": ["examplemedia.org"]
              },
              "mediaDetection": {
                "videoPostUrlMarkers": ["/customclip/"]
              },
              "fallbackScraper": {
                "htmlJsonVideoKeys": [
                  { "key": "custom_hd_stream", "minHeight": 1080 }
                ]
              }
            }
        """.trimIndent()

        val res = WebRulesRegistry.parseJson(overrideJson)
        assertTrue("Partial override should succeed", res.isSuccess)
        val cfg = res.getOrThrow()
        assertEquals("2026.12.01-hotfix", cfg.rulesVersion)

        WebRulesRegistry.applyConfig(cfg, isOverride = true)

        // Merged map keys preserve built-in defaults AND apply new ones
        assertEquals("instagram.com", DomainUtil.normalizeRootDomain("https://customshort.link/p/123"))
        assertEquals("youtube.com", DomainUtil.normalizeRootDomain("https://youtu.be/abc"))
        assertEquals("Example Media", DomainUtil.displayNameForDomain("examplemedia.org"))

        // Overridden lists take effect across utilities dynamically
        assertEquals(2_500L, PlatformRateLimiter.policyForDomain("examplemedia.org").minIntervalMs)
        assertTrue(BrowserMediaUtil.isLikelyVideoPostUrl("https://site.test/customclip/99"))

        val html = """<html><script>var data={"custom_hd_stream":"https://cdn.example.test/v.mp4"};</script></html>"""
        val scraped = FallbackMediaRules.extractMediaFromHtml(html, "https://examplemedia.org/watch")
        assertEquals(1, scraped.candidates.size)
        assertEquals(1080, scraped.candidates.first().height)
    }

    @Test
    fun malformedJson_failsGracefullyAndLeavesActiveRulesIntact() {
        val beforeVersion = WebRulesRegistry.current().rulesVersion
        val invalid = WebRulesRegistry.parseJson("{not valid json!!!")
        assertTrue(invalid.isFailure)

        val notObject = WebRulesRegistry.parseJson("[\"array\"]")
        assertTrue(notObject.isFailure)

        assertFalse(WebRulesRegistry.isUsingOverride())
        assertEquals(beforeVersion, WebRulesRegistry.current().rulesVersion)
    }
}

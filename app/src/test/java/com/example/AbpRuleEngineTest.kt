package com.example

import com.example.manager.AbpRuleEngine
import org.junit.Assert.*
import org.junit.Test

class AbpRuleEngineTest {

    @Test
    fun testDomainAndSubdomainBlocking() {
        val rules = listOf(
            "||doubleclick.net^",
            "||adservice.google.com^"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        assertTrue(ruleSet.isDomainBlocked("doubleclick.net"))
        assertTrue(ruleSet.isDomainBlocked("ad.doubleclick.net"))
        assertTrue(ruleSet.isDomainBlocked("sub.ad.doubleclick.net"))
        assertFalse(ruleSet.isDomainBlocked("notdoubleclick.net"))
        assertFalse(ruleSet.isDomainBlocked("google.com"))

        val match = ruleSet.shouldBlock("https://ad.doubleclick.net/ad.js", "example.com")
        assertNotNull(match)
        assertFalse(match!!.isAllow)
    }

    @Test
    fun testPathExceptionDoesNotWhitelistEntireDomain() {
        val rules = listOf(
            "||example.com^",
            "@@||example.com/allowed-login.js^"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        // The allowed path should NOT be blocked
        val allowedMatch = ruleSet.shouldBlock("https://example.com/allowed-login.js", "example.com")
        assertNull("Path matching @@ must be allowed", allowedMatch)

        // Other paths on example.com MUST still be blocked!
        val blockedMatch = ruleSet.shouldBlock("https://example.com/ad.png", "example.com")
        assertNotNull("Other paths on example.com must remain blocked", blockedMatch)
    }

    @Test
    fun testThirdPartyOption() {
        val rules = listOf(
            "||tracker.com^\$third-party",
            "||firstparty-only.com^\$~third-party"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        // tracker.com as third party -> blocked
        assertNotNull(ruleSet.shouldBlock("https://tracker.com/t.js", "news.com"))
        // tracker.com as first party -> allowed
        assertNull(ruleSet.shouldBlock("https://tracker.com/t.js", "tracker.com"))

        // firstparty-only.com as first party -> blocked
        assertNotNull(ruleSet.shouldBlock("https://firstparty-only.com/t.js", "firstparty-only.com"))
        // firstparty-only.com as third party -> allowed
        assertNull(ruleSet.shouldBlock("https://firstparty-only.com/t.js", "news.com"))
    }

    @Test
    fun testResourceTypeFiltering() {
        val rules = listOf(
            "||cdn.com/pixel.png\$image",
            "||cdn.com/app.js\$script"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        // image type matched -> blocked
        assertNotNull(ruleSet.shouldBlock("https://cdn.com/pixel.png", "site.com", AbpRuleEngine.ResourceType.IMAGE))
        // same url requested as script -> not blocked by image rule
        assertNull(ruleSet.shouldBlock("https://cdn.com/pixel.png", "site.com", AbpRuleEngine.ResourceType.SCRIPT))

        // script type matched -> blocked
        assertNotNull(ruleSet.shouldBlock("https://cdn.com/app.js", "site.com", AbpRuleEngine.ResourceType.SCRIPT))
        assertNull(ruleSet.shouldBlock("https://cdn.com/app.js", "site.com", AbpRuleEngine.ResourceType.STYLESHEET))
    }

    @Test
    fun testDomainOptionConstraint() {
        val rules = listOf(
            "||banner.com^\$domain=news.com|blog.org",
            "||widget.com^\$domain=~safe-news.com"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        // banner.com loaded from news.com -> blocked
        assertNotNull(ruleSet.shouldBlock("https://banner.com/img.jpg", "news.com"))
        // banner.com loaded from other.com -> allowed
        assertNull(ruleSet.shouldBlock("https://banner.com/img.jpg", "other.com"))

        // widget.com loaded from safe-news.com -> allowed (excluded)
        assertNull(ruleSet.shouldBlock("https://widget.com/w.js", "safe-news.com"))
        // widget.com loaded from regular site -> blocked
        assertNotNull(ruleSet.shouldBlock("https://widget.com/w.js", "random.com"))
    }

    @Test
    fun testRegexAndUnsupportedFiltersAreSkippedSafely() {
        val rules = listOf(
            "/ad[0-9]+/",
            "||example.com^\$redirect=noopjs",
            "||bad.com^\$badfilter",
            "||csp.com^\$csp=default-src 'none'",
            "##+js(set, test, true)"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())
        assertEquals(5, ruleSet.totalRulesSkipped)
        assertEquals(0, ruleSet.allowRules.size)
        assertEquals(0, ruleSet.blockRules.size)
        assertEquals(0, ruleSet.domainOnlyBlock.size)
    }

    @Test
    fun testCosmeticFiltersGlobalDomainAndException() {
        val rules = listOf(
            "##.ad-banner",
            "news.com##.sponsor-post",
            "~safenews.com##.promoted",
            "special.news.com#@#.sponsor-post"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())

        val newsSelectors = ruleSet.cosmetic.selectorsFor("news.com")
        assertTrue(newsSelectors.contains(".ad-banner"))
        assertTrue(newsSelectors.contains(".sponsor-post"))

        val specialNewsSelectors = ruleSet.cosmetic.selectorsFor("special.news.com")
        assertTrue(specialNewsSelectors.contains(".ad-banner"))
        assertFalse(specialNewsSelectors.contains(".sponsor-post")) // un-hidden by #@#

        val otherSelectors = ruleSet.cosmetic.selectorsFor("blog.org")
        assertTrue(otherSelectors.contains(".ad-banner"))
        assertFalse(otherSelectors.contains(".sponsor-post"))
    }

    @Test
    fun testRegistrableDomainCoId() {
        assertEquals("tokopedia.com", AbpRuleEngine.getRegistrableDomain("www.tokopedia.com"))
        assertEquals("tokopedia.co.id", AbpRuleEngine.getRegistrableDomain("shop.tokopedia.co.id"))
        assertEquals("kompas.com", AbpRuleEngine.getRegistrableDomain("megapolitan.kompas.com"))
        assertEquals("bbc.co.uk", AbpRuleEngine.getRegistrableDomain("news.bbc.co.uk"))

        // isThirdParty comparison
        assertFalse(AbpRuleEngine.isThirdParty("api.tokopedia.co.id", "tokopedia.co.id"))
        assertTrue(AbpRuleEngine.isThirdParty("tracker.adnetwork.com", "tokopedia.co.id"))
    }

    @Test
    fun testInferType() {
        assertEquals(AbpRuleEngine.ResourceType.SCRIPT, AbpRuleEngine.inferType("https://example.com/app.js?v=1"))
        assertEquals(AbpRuleEngine.ResourceType.STYLESHEET, AbpRuleEngine.inferType("https://example.com/style.css"))
        assertEquals(AbpRuleEngine.ResourceType.IMAGE, AbpRuleEngine.inferType("https://example.com/logo.png"))
        assertEquals(AbpRuleEngine.ResourceType.MEDIA, AbpRuleEngine.inferType("https://example.com/stream.m3u8"))
        assertEquals(AbpRuleEngine.ResourceType.FONT, AbpRuleEngine.inferType("https://example.com/font.woff2"))
        assertEquals(AbpRuleEngine.ResourceType.WEBSOCKET, AbpRuleEngine.inferType("wss://example.com/socket"))
        assertEquals(AbpRuleEngine.ResourceType.PING, AbpRuleEngine.inferType("https://google-analytics.com/collect"))
        assertEquals(AbpRuleEngine.ResourceType.XMLHTTPREQUEST, AbpRuleEngine.inferType("https://example.com/api/data", "application/json"))
    }
}

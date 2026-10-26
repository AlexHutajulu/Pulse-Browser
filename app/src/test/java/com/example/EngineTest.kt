package com.example

import com.example.manager.AbpRuleEngine
import com.example.manager.ScriptletEngine
import com.yourbrowser.adblock.HeuristicsEngine
import org.junit.Assert.*
import org.junit.Test

class EngineTest {

    @Test
    fun testScriptletRulesAreParsedNotDiscarded() {
        val rules = listOf(
            "example.com##+js(set-constant, adsbygoogle, true)",
            "example.com##+js(abort-on-property-read, adBlockDetected)",
            "other.com##+js(set-constant, foo, 123)"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())
        val forExample = ruleSet.scriptlets.scriptletsFor("www.example.com")
        assertEquals(2, forExample.size)
        assertTrue(forExample.any { it.name == "set-constant" && it.args == listOf("adsbygoogle", "true") })
        assertTrue(forExample.any { it.name == "abort-on-property-read" })
        // Aturan other.com tidak berlaku untuk example.com
        assertTrue(ruleSet.scriptlets.scriptletsFor("example.com").none { it.args.contains("foo") })
    }

    @Test
    fun testScriptletExceptionRemovesRule() {
        val rules = listOf(
            "example.com##+js(set-constant, foo, true)",
            "example.com#@#+js(set-constant, foo, true)"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())
        assertTrue(ruleSet.scriptlets.scriptletsFor("example.com").isEmpty())
    }

    @Test
    fun testUnsupportedScriptletIsNotExecuted() {
        val rules = listOf(
            "example.com##+js(some-unknown-scriptlet, foo)"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())
        // Diparsing (tersimpan), tapi scriptletsFor() menyaringnya karena
        // tidak ada di ScriptletEngine.SUPPORTED_SCRIPTLETS.
        assertTrue(ruleSet.scriptlets.scriptletsFor("example.com").isEmpty())
    }

    @Test
    fun testAdGuardScriptletSyntax() {
        val parsed = ScriptletEngine.parseScriptletRule(
            "example.com#%#//scriptlet('set-constant', 'foo', 'true')"
        )
        assertNotNull(parsed)
        assertEquals("set-constant", parsed!!.name)
        assertEquals(listOf("foo", "true"), parsed.args)
    }

    @Test
    fun testScriptletInvocationJsEscapesQuotes() {
        val ruleSet = ScriptletEngine.ScriptletRuleSet(
            listOf(
                ScriptletEngine.ScriptletRule(
                    setOf("example.com"), "set-constant", listOf("foo", "a\"b"), false
                )
            )
        )
        val js = ScriptletEngine.buildInvocationJs("example.com", ruleSet)
        assertTrue(js.contains("set-constant"))
        assertTrue(js.contains("a\\\"b"))
        assertFalse(js.contains("a\"b"))
    }

    @Test
    fun testHeuristicDomain() {
        assertEquals(
            "heuristic-domain",
            HeuristicsEngine.heuristicReason(
                "ads.doubleclick.net", "https://ads.doubleclick.net/x.js", false
            )
        )
        assertNull(
            HeuristicsEngine.heuristicReason(
                "news.example.com", "https://news.example.com/article", false
            )
        )
    }

    @Test
    fun testHeuristicGamblingAndPathThirdPartyOnly() {
        // Third-party + kata kunci gambling di host -> blok
        assertEquals(
            "heuristic-gambling",
            HeuristicsEngine.heuristicReason(
                "kubet123.example.com", "https://kubet123.example.com/", false
            )
        )
        // First-party dengan path iklan -> TIDAK diblok lapisan heuristik
        // (ABP tetap yang memutuskan; ini sengaja untuk melindungi
        // first-party yang sah, berbeda dari  asli).
        assertNull(
            HeuristicsEngine.heuristicReason(
                "news.example.com", "https://news.example.com/popunder/page", true
            )
        )
        // Third-party dengan pola path -> blok
        assertEquals(
            "heuristic-path",
            HeuristicsEngine.heuristicReason(
                "cdn.example.com", "https://cdn.example.com/popunder.js", false
            )
        )
    }

    @Test
    fun testAbpExceptionStillWinsOverHeuristicDomain() {
        // Exception ABP harus menang atas lapisan heuristik .
        // Di AdBlockWebViewClient.checkRules, sebelum heuristik dipanggil,
        // matchesAllowRule() dicek dulu; kalau true, heuristik dilewati.
        val rules = listOf(
            "@@||doubleclick.net^",
            "||doubleclick.net^"
        )
        val ruleSet = AbpRuleEngine.build(rules.asSequence())
        assertNull(ruleSet.shouldBlock("https://doubleclick.net/ad.js", "news.com"))
        assertTrue(ruleSet.matchesAllowRule("https://doubleclick.net/ad.js", "news.com"))
        // Tanpa exception, matchesAllowRule harus false.
        val rules2 = listOf("||doubleclick.net^")
        val ruleSet2 = AbpRuleEngine.build(rules2.asSequence())
        assertFalse(ruleSet2.matchesAllowRule("https://doubleclick.net/ad.js", "news.com"))
    }
}

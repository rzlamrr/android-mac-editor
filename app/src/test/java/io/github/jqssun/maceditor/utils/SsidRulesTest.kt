package io.github.jqssun.maceditor.utils

import io.github.jqssun.maceditor.utils.SsidRules.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SsidRulesTest {
    private val home = Rule("Home", "02:11:22:AA:BB:CC")
    private val off = Rule("Off", "02:11:22:AA:BB:DD", enabled = false)
    private val rules = listOf(home, off)

    @Test fun quotesAreStripped() {
        assertEquals("Home", SsidRules.normalizeSsid("\"Home\""))
        assertEquals(home, SsidRules.find(rules, "\"Home\""))
        assertEquals(home, SsidRules.find(rules, "Home"))
    }

    @Test fun onlyOneQuotePairStripped() = assertEquals("\"x\"", SsidRules.normalizeSsid("\"\"x\"\""))

    @Test fun lonelyQuoteKept() = assertEquals("\"", SsidRules.normalizeSsid("\""))

    @Test fun matchIsCaseSensitive() {
        assertNull(SsidRules.find(rules, "\"home\""))
        assertNull(SsidRules.find(rules, "\"HOME\""))
    }

    @Test fun hiddenOrEmptySsidNeverMatches() {
        assertNull(SsidRules.find(rules, null))
        assertNull(SsidRules.find(rules, ""))
        assertNull(SsidRules.find(rules, "\"\""))
        assertNull(SsidRules.find(listOf(Rule("", "02:11:22:AA:BB:CC")), "\"\""))
    }

    @Test fun disabledRuleIgnored() = assertNull(SsidRules.find(rules, "\"Off\""))

    @Test fun noRule() {
        assertNull(SsidRules.find(rules, "\"Other\""))
        assertNull(SsidRules.find(emptyList(), "\"Home\""))
    }

    @Test fun disabledDuplicateDoesNotShadowEnabled() {
        val list = listOf(Rule("Home", "02:00:00:00:00:01", enabled = false), home)
        assertEquals(home, SsidRules.find(list, "\"Home\""))
    }

    @Test fun hexSsidDoesNotMatchTextRule() = assertNull(SsidRules.find(rules, "486f6d65"))

    @Test fun jsonRoundTrip() {
        assertEquals(rules, SsidRules.fromJson(SsidRules.toJson(rules)))
        assertEquals(listOf(Rule("a\"b", "02:00:00:00:00:01")), SsidRules.fromJson(SsidRules.toJson(listOf(Rule("a\"b", "02:00:00:00:00:01")))))
    }

    @Test fun badJsonIsEmpty() {
        assertTrue(SsidRules.fromJson(null).isEmpty())
        assertTrue(SsidRules.fromJson("{not json").isEmpty())
        assertEquals(listOf(home), SsidRules.fromJson("[{\"ssid\":\"\"},5,{\"ssid\":\"Home\",\"mac\":\"02:11:22:AA:BB:CC\"}]"))
    }

    @Test fun migrationDefault() {
        assertTrue(SsidRules.resolvePerSsidMode(null, "", false))
        assertFalse(SsidRules.resolvePerSsidMode(null, "02:11:22:AA:BB:CC", false))
        assertTrue(SsidRules.resolvePerSsidMode(null, "02:11:22:AA:BB:CC", true))
        assertTrue(SsidRules.resolvePerSsidMode(true, "02:11:22:AA:BB:CC", false))
        assertFalse(SsidRules.resolvePerSsidMode(false, "", true))
    }

    @Test fun wifiMacsFollowsMode() {
        assertEquals(listOf(home.mac, off.mac), SsidRules.wifiMacs(true, "x", rules))
        assertEquals(listOf("x"), SsidRules.wifiMacs(false, "x", rules))
    }
}

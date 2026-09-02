package com.helix.runtime.quickjs

import app.cash.zipline.QuickJs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HXA-050 spike capability 1 (roadmap M5): `QuickJs.evaluate` returns correct values.
 *
 * Values are PINNED to the probe observations on API 29 + API 36 arm64-v8a (HXA-050
 * completion record): a Zipline upgrade that changes the conversion behavior (e.g. JS
 * number → Long instead of Integer) or the engine version fails these tests by design.
 */
class QuickJsEvaluateSpikeTest {
    private lateinit var quickJs: QuickJs

    @Before
    fun createInstance() {
        quickJs = QuickJs.create()
    }

    @After
    fun closeInstance() {
        quickJs.close()
    }

    @Test
    fun arithmeticExpressionReturnsExactValue() {
        // Probe observation: small JS integers convert to java.lang.Integer.
        assertEquals(7, quickJs.evaluate("1 + 2 * 3"))
    }

    @Test
    fun stringExpressionReturnsJavaString() {
        assertEquals("ab", quickJs.evaluate("'a' + 'b'"))
    }

    @Test
    fun unicodeRoundTripIncludesAccentsEmojiAndCjk() {
        assertEquals("héllo 🚀 日本語", quickJs.evaluate("'héllo 🚀 日本語'"))
    }

    @Test
    fun largeStringOverOneMiBSurvivesConversion() {
        // doc 03 §10 attack surface: 大字符串 (Unicode / 大输出) must round-trip.
        val result = quickJs.evaluate("'a'.repeat(2 * 1024 * 1024)")
        val string = result as? String
        assertTrue("expected a String result, got ${result?.javaClass?.name}", string != null)
        assertEquals(2 * 1024 * 1024, string!!.length)
        assertEquals('a', string[0])
        assertEquals('a', string[string.length - 1])
    }

    @Test
    fun jsonStringifyRoundTrip() {
        assertEquals(
            """{"a":1,"unicode":"🚀"}""",
            quickJs.evaluate("(function(){ return JSON.stringify({a: 1, unicode: '🚀'}); })()"),
        )
    }

    @Test
    fun engineVersionIsExposed() {
        // Probe observation on both devices: QuickJS upstream version string.
        assertEquals("2021-03-27", QuickJs.version)
    }
}

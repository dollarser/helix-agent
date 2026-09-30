package com.helix.feature.browser.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptEngineTest {
    @Test
    fun matches_wildcardStar() {
        assertTrue(UserScriptEngine.matches("*", "https://github.com"))
        assertTrue(UserScriptEngine.matches("", "https://bilibili.com"))
    }

    @Test
    fun matches_domainWildcard() {
        val pattern = "*://*.bilibili.com/*"
        assertTrue(UserScriptEngine.matches(pattern, "https://www.bilibili.com/video/BV12345"))
        assertTrue(UserScriptEngine.matches(pattern, "http://space.bilibili.com/12345"))
        assertFalse(UserScriptEngine.matches(pattern, "https://github.com/torvalds/linux"))
    }

    @Test
    fun matches_prefixWildcard() {
        val pattern = "https://github.com/*"
        assertTrue(UserScriptEngine.matches(pattern, "https://github.com/torvalds"))
        assertFalse(UserScriptEngine.matches(pattern, "https://gitlab.com/torvalds"))
    }

    @Test fun scriptNameIsOneEncodedLiteralEvenWithQuotesBackslashesAndLineBreaks() {
        listOf("quote'\"slash\\", "first\nsecond", "\\');throw new Error('name'); //").forEach { name ->
            val wrapped = UserScriptEngine.wrapScript(name, "return 7;")
            val line = wrapped.lineSequence().single { it.trimStart().startsWith("console.error(") }.trim()
            val literal = line.removePrefix("console.error(").removeSuffix(", e);")
            assertEquals("[Helix UserScript: $name]", Json.parseToJsonElement(literal).jsonPrimitive.content)
            assertTrue(wrapped.contains("return 7;"))
        }
    }

    @Test
    fun wrapScript_wrapsInIife() {
        val wrapped = UserScriptEngine.wrapScript("TestScript", "alert('hi');")
        assertTrue(wrapped.contains("(function()"))
        assertTrue(wrapped.contains("alert('hi');"))
        assertTrue(wrapped.contains("console.error(\"[Helix UserScript: TestScript]\""))
    }
}

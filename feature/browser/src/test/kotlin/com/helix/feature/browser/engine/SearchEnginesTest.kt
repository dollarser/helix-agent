package com.helix.feature.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEnginesTest {
    @Test
    fun resolveInput_emptyOrBlank() {
        assertEquals("", SearchEngines.resolveInput(""))
        assertEquals("", SearchEngines.resolveInput("   "))
    }

    @Test
    fun resolveInput_alreadyHasScheme() {
        assertEquals("https://github.com", SearchEngines.resolveInput("https://github.com"))
        assertEquals("http://example.com/path", SearchEngines.resolveInput("http://example.com/path"))
        assertEquals("about:blank", SearchEngines.resolveInput("about:blank"))
        assertEquals("data:text/html,<h1>Hello</h1>", SearchEngines.resolveInput("data:text/html,<h1>Hello</h1>"))
    }

    @Test
    fun resolveInput_domainOrIp() {
        assertEquals("https://github.com", SearchEngines.resolveInput("github.com"))
        assertEquals("https://v2ex.com/t/12345", SearchEngines.resolveInput("v2ex.com/t/12345"))
        assertEquals("https://localhost:8080", SearchEngines.resolveInput("localhost:8080"))
        assertEquals("https://192.168.1.1:80", SearchEngines.resolveInput("192.168.1.1:80"))
    }

    @Test
    fun resolveInput_searchQuery() {
        assertEquals(
            "https://www.google.com/search?q=kotlin+coroutines",
            SearchEngines.resolveInput("kotlin coroutines", SearchEngines.GOOGLE),
        )
        assertEquals(
            "https://www.bing.com/search?q=helix+agent",
            SearchEngines.resolveInput("helix agent", SearchEngines.BING),
        )
        assertEquals(
            "https://www.baidu.com/s?wd=android+browser",
            SearchEngines.resolveInput("android browser", SearchEngines.BAIDU),
        )
        assertEquals(
            "https://duckduckgo.com/?q=weather",
            SearchEngines.resolveInput("weather", SearchEngines.DUCKDUCKGO),
        )
    }
}
